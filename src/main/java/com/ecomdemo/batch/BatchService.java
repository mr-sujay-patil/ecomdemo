package com.ecomdemo.batch;

import com.ecomdemo.batch.dto.JobExecutionResponse;
import com.ecomdemo.batch.dto.ProductImportResponse;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Starts jobs and reports what they did.
 *
 * <h2>Why an upload is written to disk first</h2>
 *
 * <p>The obvious implementation streams the multipart body straight into the reader and never
 * touches the filesystem. It also makes the job unrestartable: a restart re-reads the SAME input
 * from the SAME path, and an HTTP request body is gone the moment the response is written. So
 * every upload is staged under a name of its own and the job is given that path.
 *
 * <p>The name is a UUID plus the original filename. The UUID is not decoration either: the input
 * path is the import job's only identifying parameter, so a unique path is what makes each
 * upload a new {@code JobInstance}. Reusing the original name would mean the second upload of
 * {@code products.csv} was treated as a restart of the first — or, if the first had completed,
 * refused outright.
 *
 * <h2>Why the run is synchronous</h2>
 *
 * <p>Spring Batch's default {@code TaskExecutor} is a {@code SyncTaskExecutor}, so
 * {@link JobOperator#start} returns when the job has finished and the HTTP response can carry the
 * real counters. For a catalogue import triggered by a human that is the honest design: the
 * alternative — 202 Accepted and a polling endpoint — buys nothing until jobs run long enough
 * for a request to time out, and this one processes ten thousand rows in seconds. The lookup
 * endpoint exists regardless, because the record outlives the request either way.
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final BatchProperties properties;
    private final Map<String, Job> jobsByName;

    public BatchService(JobOperator jobOperator, JobRepository jobRepository,
            BatchProperties properties, List<Job> jobs) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.jobsByName = jobs.stream().collect(Collectors.toMap(Job::getName, Function.identity()));
    }

    /** Stages the upload, runs the import against it, and reports the outcome. */
    public ProductImportResponse importProducts(MultipartFile file) {
        Path staged = stage(file);
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchJobs.PARAM_INPUT_FILE, staged.toAbsolutePath().toString())
                .toJobParameters();
        JobExecution execution = run(BatchJobs.PRODUCT_IMPORT, parameters);
        return importResponse(execution, staged);
    }

    /**
     * Runs the sales report for one day. Called by the scheduler, and by the tests that prove the
     * report itself is right without waiting for 02:00.
     */
    public JobExecutionResponse runSalesReport(LocalDate day) {
        JobParameters parameters = new JobParametersBuilder()
                .addLocalDate(BatchJobs.PARAM_REPORT_DATE, day)
                .toJobParameters();
        return JobExecutionResponse.from(run(BatchJobs.SALES_REPORT, parameters));
    }

    /** One attempt, by its id. */
    public JobExecutionResponse findExecution(long executionId) {
        return JobExecutionResponse.from(requireExecution(executionId));
    }

    /**
     * Restarts a failed attempt.
     *
     * <p>There is no special "restart" call here, and that is the point worth understanding:
     * starting a job with the same identifying parameters as a FAILED execution <em>is</em> a
     * restart. Spring Batch finds the existing {@code JobInstance}, creates a second
     * {@code JobExecution} against it, restores each step's saved ExecutionContext, and lets the
     * reader pick up where the last commit left off.
     *
     * <p>The two refusals below are the framework's, surfaced as 409s rather than 500s. A
     * COMPLETED instance cannot be re-run at all — that is the guarantee that a nightly job
     * cannot double-post — and an attempt that is still running cannot be restarted from
     * underneath itself.
     */
    public ProductImportResponse restartProductImport(long executionId) {
        JobExecution failed = requireExecution(executionId);
        if (!BatchJobs.PRODUCT_IMPORT.equals(failed.getJobInstance().getJobName())) {
            throw new ConflictException(
                    "Execution %d is not a product import".formatted(executionId));
        }
        if (failed.getStatus() != BatchStatus.FAILED) {
            throw new ConflictException(
                    "Execution %d is %s; only a FAILED execution can be restarted"
                            .formatted(executionId, failed.getStatus()));
        }
        JobParameters parameters = failed.getJobParameters();
        JobExecution restarted = run(BatchJobs.PRODUCT_IMPORT, parameters);
        Path input = Path.of(parameters.getString(BatchJobs.PARAM_INPUT_FILE));
        return importResponse(restarted, input);
    }

    private ProductImportResponse importResponse(JobExecution execution, Path input) {
        Path errors = ProductImportJobConfig.errorFileFor(input);
        return new ProductImportResponse(
                JobExecutionResponse.from(execution),
                input.toString(),
                Files.exists(errors) ? errors.toString() : null);
    }

    private JobExecution run(String jobName, JobParameters parameters) {
        Job job = jobsByName.get(jobName);
        if (job == null) {
            throw new IllegalStateException("No job named " + jobName);
        }
        try {
            JobExecution execution = jobOperator.start(job, parameters);
            log.info("{} execution {} finished {} ({})", jobName, execution.getId(),
                    execution.getStatus(), execution.getExitStatus().getExitCode());
            return execution;
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new ConflictException(
                    "This work has already been completed successfully and will not be run again: "
                            + e.getMessage());
        } catch (JobExecutionAlreadyRunningException e) {
            throw new ConflictException("This job is already running: " + e.getMessage());
        } catch (JobRestartException | InvalidJobParametersException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    private JobExecution requireExecution(long executionId) {
        JobExecution execution = jobRepository.getJobExecution(executionId);
        if (execution == null) {
            throw new NotFoundException("Job execution %d not found".formatted(executionId));
        }
        return execution;
    }

    /**
     * Copies the upload into the staging directory under a unique name.
     *
     * <p>{@code Path.of(originalFilename).getFileName()} strips any directory part the client
     * sent. A multipart filename is attacker-controlled text, and {@code ../../etc/passwd} as a
     * filename is the oldest upload bug there is.
     */
    private Path stage(MultipartFile file) {
        String submitted = file.getOriginalFilename();
        String safeName = submitted == null || submitted.isBlank()
                ? "products.csv"
                : Path.of(submitted).getFileName().toString();
        Path target = properties.uploadDirectory()
                .resolve("%s-%s".formatted(UUID.randomUUID(), safeName));
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage the uploaded file at " + target, e);
        }
        log.info("staged upload '{}' as {} ({} bytes)", safeName, target, file.getSize());
        return target;
    }
}

package com.ecomdemo.catalog.search;

import com.ecomdemo.shared.NotFoundException;
import java.util.stream.Collectors;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Starts the backfill and reports on it (Phase 28). */
@Service
public class EmbeddingBackfillService {

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job backfill;
    private final ProductSearchIndex index;
    private final JdbcTemplate jdbc;

    public EmbeddingBackfillService(JobOperator jobOperator, JobRepository jobRepository, Job productEmbeddingBackfill,
            ProductSearchIndex index, JdbcTemplate jdbc) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.backfill = productEmbeddingBackfill;
        this.index = index;
        this.jdbc = jdbc;
    }

    /**
     * Starts a run and returns at once (the job runs on its own executor). Refused with a 503 when
     * no model is configured: a job that could only fail is not worth a row in the repository.
     *
     * <p>A {@code run.id} parameter makes every call a NEW job instance. Spring Batch refuses to run
     * an instance that already COMPLETED with the same parameters - right for "yesterday's sales
     * report", wrong for "embed everything again", which is always a fresh request.
     */
    public BackfillStatus start() {
        index.requireConfigured();
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        try {
            return status(jobOperator.start(backfill, parameters));
        } catch (Exception e) {
            throw new IllegalStateException("The embedding backfill could not be started", e);
        }
    }

    public BackfillStatus status(long executionId) {
        JobExecution execution;
        try {
            execution = jobRepository.getJobExecution(executionId);
        } catch (EmptyResultDataAccessException e) {
            // Spring Batch 6.0's JDBC DAO throws for an unknown id instead of returning the null its
            // signature promises (found by the test asking for 999999). Both mean "no such run".
            execution = null;
        }
        if (execution == null || !EmbeddingBackfillJobConfig.JOB.equals(execution.getJobInstance().getJobName())) {
            throw new NotFoundException("No embedding backfill with execution id " + executionId);
        }
        return status(execution);
    }

    private BackfillStatus status(JobExecution execution) {
        long read = execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum();
        long written = execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
        // The exceptions themselves are NOT persisted - a run read back from the repository, as any
        // pod but the one running it reads it, has none. What is stored is each step's exit
        // description: the stack trace as text. Spring Batch 6 wraps a writer's exception in
        // "FatalStepExecutionException: Unable to process chunk", so the useful line is the ROOT
        // cause - the last "Caused by:" - and the first line only when there is none.
        String failure = execution.getStepExecutions().stream()
                .map(step -> step.getExitStatus().getExitDescription())
                .filter(description -> description != null && !description.isBlank())
                .map(EmbeddingBackfillService::rootCause)
                .distinct()
                .collect(Collectors.joining("; "));
        return new BackfillStatus(execution.getId(), execution.getStatus().name(), read, written,
                execution.getStartTime(), execution.getEndTime(), failure.isEmpty() ? null : failure,
                count("SELECT count(*) FROM product_embedding"), count("SELECT count(*) FROM product"));
    }

    static String rootCause(String stackTrace) {
        String first = stackTrace.lines().findFirst().orElse(stackTrace);
        return stackTrace.lines()
                .filter(line -> line.startsWith("Caused by: "))
                .reduce((earlier, later) -> later)
                .map(line -> line.substring("Caused by: ".length()))
                .orElse(first);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}

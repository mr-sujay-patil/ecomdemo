package com.ecomdemo.batch.dto;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.batch.core.job.JobExecution;

/**
 * One attempt at a job, as the API reports it.
 *
 * <p>The two ids are both here and they are not the same thing. {@code instanceId} identifies
 * the <em>unit of work</em> — this job name with these identifying parameters, so "the import of
 * this file" — and {@code id} identifies one <em>attempt</em> at it. A restart produces a second
 * execution against the same instance, which is exactly how the client can tell a resumed run
 * from a fresh one.
 *
 * @param id the JobExecution id, and what the restart and lookup endpoints take
 * @param instanceId the JobInstance this attempt belongs to
 * @param jobName which job ran
 * @param status COMPLETED, FAILED, STARTED, ...
 * @param exitCode the exit status, which a job may set to something finer than its BatchStatus
 * @param startTime when this attempt began; null if it never started
 * @param endTime when it finished; null while it is running
 * @param readCount items read, summed over the steps
 * @param writeCount items written, summed over the steps
 * @param skipCount items skipped, summed over the steps
 * @param failureMessage the first failure, if the attempt failed
 * @param steps the per-step detail
 */
public record JobExecutionResponse(long id, long instanceId, String jobName, String status,
        String exitCode, LocalDateTime startTime, LocalDateTime endTime, long readCount,
        long writeCount, long skipCount, String failureMessage,
        List<StepExecutionResponse> steps) {

    /** How many links of a cause chain the failure message shows. */
    private static final int MAX_CAUSE_CHAIN = 4;

    public static JobExecutionResponse from(JobExecution execution) {
        List<StepExecutionResponse> steps = execution.getStepExecutions().stream()
                .map(StepExecutionResponse::from)
                .toList();
        return new JobExecutionResponse(
                execution.getId(),
                execution.getJobInstanceId(),
                execution.getJobInstance().getJobName(),
                execution.getStatus().toString(),
                execution.getExitStatus().getExitCode(),
                execution.getStartTime(),
                execution.getEndTime(),
                steps.stream().mapToLong(StepExecutionResponse::readCount).sum(),
                steps.stream().mapToLong(StepExecutionResponse::writeCount).sum(),
                steps.stream().mapToLong(StepExecutionResponse::skipCount).sum(),
                firstFailure(execution),
                steps);
    }

    /**
     * The first failure, rendered as its chain of causes rather than only its outermost link.
     *
     * <p>That matters more than it sounds. The framework's own wrapper is almost always the
     * useless one — a skip limit being exceeded reaches the top as
     * {@code FatalStepExecutionException: Unable to process chunk}, and the sentence an operator
     * actually needs ("Skip limit of '50' exceeded", then the row that tipped it over) is two
     * links down. The chain is capped so that a deeply nested framework failure cannot turn one
     * field of a JSON response into a wall of text.
     */
    private static String firstFailure(JobExecution execution) {
        return execution.getAllFailureExceptions().stream()
                .findFirst()
                .map(JobExecutionResponse::describeChain)
                .orElse(null);
    }

    private static String describeChain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int link = 0; current != null && link < MAX_CAUSE_CHAIN; link++) {
            if (link > 0) {
                text.append("; caused by ");
            }
            text.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) {
                text.append(": ").append(current.getMessage());
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return text.toString();
    }
}

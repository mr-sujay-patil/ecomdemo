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

    private static String firstFailure(JobExecution execution) {
        return execution.getAllFailureExceptions().stream()
                .findFirst()
                .map(t -> t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage()))
                .orElse(null);
    }
}

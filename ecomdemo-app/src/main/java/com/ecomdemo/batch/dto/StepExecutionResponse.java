package com.ecomdemo.batch.dto;

import org.springframework.batch.core.step.StepExecution;

/**
 * What one step of a run did, in numbers.
 *
 * <p>These counters are the reason a batch framework is worth its ceremony: they are recorded in
 * {@code BATCH_STEP_EXECUTION} as the step runs, so they survive the process that produced them
 * and can be read back days later.
 *
 * @param name the step's name
 * @param status COMPLETED, FAILED, STOPPED, ...
 * @param readCount items the reader produced
 * @param writeCount items the writer accepted
 * @param skipCount items skipped, in total, across reading, processing and writing
 * @param commitCount transactions committed - one per chunk, plus replays
 * @param rollbackCount transactions rolled back; a non-zero value with a COMPLETED status is the
 *     signature of the item-by-item replay that finding a skippable item requires
 */
public record StepExecutionResponse(String name, String status, long readCount, long writeCount,
        long skipCount, long commitCount, long rollbackCount) {

    public static StepExecutionResponse from(StepExecution step) {
        return new StepExecutionResponse(
                step.getStepName(),
                step.getStatus().toString(),
                step.getReadCount(),
                step.getWriteCount(),
                step.getSkipCount(),
                step.getCommitCount(),
                step.getRollbackCount());
    }
}

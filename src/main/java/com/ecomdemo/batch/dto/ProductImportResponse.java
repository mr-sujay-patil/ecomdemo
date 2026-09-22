package com.ecomdemo.batch.dto;

/**
 * The answer to an upload: what the job did, and where to look at what it refused.
 *
 * <p>{@code errorFile} is null when the import rejected nothing, so its presence is the signal
 * that the file needs attention — rather than an empty file the operator has to open to find out
 * it is empty.
 *
 * @param execution the run itself, including its counters
 * @param inputFile where the upload was staged; the parameter a restart re-runs against
 * @param errorFile the rejected rows, or null if there were none
 */
public record ProductImportResponse(JobExecutionResponse execution, String inputFile,
        String errorFile) {
}

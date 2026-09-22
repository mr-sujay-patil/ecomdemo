package com.ecomdemo.batch;

/**
 * The names the two jobs are registered under, and the job parameters they take.
 *
 * <p>Job names and parameter keys are not decoration: a job's name plus its <em>identifying</em>
 * parameters is the primary key of a {@code BATCH_JOB_INSTANCE} row. Change the string
 * {@code "inputFile"} and yesterday's runs stop being recognisable as the same instance, so
 * these belong in one place rather than repeated as literals across a config, a service and a
 * test.
 */
public final class BatchJobs {

    /** Job 1: read a product CSV, validate it, and upsert the catalogue from it. */
    public static final String PRODUCT_IMPORT = "productImportJob";

    /** Job 2: write a CSV of one day's order count, revenue and best sellers. */
    public static final String SALES_REPORT = "salesReportJob";

    /**
     * The absolute path of the file to import. Identifying, and deliberately the ONLY identifying
     * parameter of the import job: re-running with the same path is a restart of the same
     * instance, which is exactly what a restart has to be.
     */
    public static final String PARAM_INPUT_FILE = "inputFile";

    /**
     * The day the report covers. Identifying, so each day is its own instance and yesterday's
     * report cannot be silently overwritten by a re-run.
     */
    public static final String PARAM_REPORT_DATE = "reportDate";

    private BatchJobs() {
        // constants only
    }
}

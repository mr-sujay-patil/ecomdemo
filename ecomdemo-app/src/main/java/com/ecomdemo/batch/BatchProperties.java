package com.ecomdemo.batch;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The batch feature's settings, bound from {@code ecomdemo.batch.*}.
 *
 * <p>A record rather than a class with setters: the values are read once at startup and never
 * change, and constructor binding makes that impossible to get wrong. The defaults in the
 * canonical constructor are what a plain {@code ./mvnw spring-boot:run} gets, so the feature
 * works with no configuration at all.
 *
 * <p>{@link #directory()} is a base rather than three separate paths because the three
 * sub-directories belong together: an uploaded file, the error file listing the rows that were
 * rejected from it, and the reports. One volume mount in compose covers all of them.
 *
 * @param directory base directory for everything the jobs read and write
 * @param chunkSize rows read and processed per transaction in the import
 * @param skipLimit how many rows the import may reject before the job fails
 * @param salesReportCron six-field cron for the daily report, or {@code -} to disable it
 */
@ConfigurationProperties(prefix = "ecomdemo.batch")
public record BatchProperties(Path directory, int chunkSize, int skipLimit,
        String salesReportCron) {

    public BatchProperties {
        directory = directory == null ? Path.of("./batch") : directory;
        chunkSize = chunkSize <= 0 ? 100 : chunkSize;
        skipLimit = skipLimit < 0 ? 50 : skipLimit;
        salesReportCron = salesReportCron == null ? "0 0 2 * * *" : salesReportCron;
    }

    /** Where an upload is staged before the job reads it. */
    public Path uploadDirectory() {
        return directory.resolve("uploads");
    }

    /** Where the generated sales reports land. */
    public Path reportDirectory() {
        return directory.resolve("reports");
    }
}

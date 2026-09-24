package com.ecomdemo.batch;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the sales report every night.
 *
 * <p>The cron is a property, not a literal, for two reasons. An operator can move it without a
 * rebuild; and Spring's {@code "-"} turns the schedule off entirely, which is how an environment
 * that should not generate reports — a developer's laptop, a second instance — opts out.
 *
 * <p>Six fields, not five: Spring's cron starts at SECONDS, so the familiar five-field Unix
 * expression is off by one field here and {@code 0 2 * * *} would mean "the 2nd minute of every
 * hour", silently.
 *
 * <p>The report covers <strong>yesterday</strong>. A job that ran at 02:00 and reported on "today"
 * would report on two hours of it. Yesterday is also what makes the date a good identifying job
 * parameter: one instance per day, so a second run for the same day is refused by the
 * JobRepository rather than quietly rewriting the file.
 *
 * <h2>What {@code @Scheduled} is and is not</h2>
 *
 * <p>It is a timer inside this JVM. Every instance of the application that is running will fire
 * it, so with two instances the report runs twice — and the second one is refused by the
 * JobRepository, because the day's instance is already COMPLETE. That refusal is not a
 * distributed scheduler, but it is a real safety net, and it is one of the better arguments for
 * keeping job state in the database rather than in memory. A proper answer (a leader election, or
 * an external scheduler calling an endpoint) belongs to a later phase.
 */
@Configuration
@EnableScheduling
class SalesReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(SalesReportScheduler.class);

    private final BatchService batchService;

    SalesReportScheduler(BatchService batchService) {
        this.batchService = batchService;
    }

    @Scheduled(cron = "${ecomdemo.batch.sales-report-cron}")
    void runDailySalesReport() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("scheduled sales report starting for {}", yesterday);
        try {
            batchService.runSalesReport(yesterday);
        } catch (RuntimeException e) {
            // A scheduled method that throws kills nothing but its own run - the next one still
            // fires - but the exception would otherwise be logged by the framework with no
            // context about what it was doing. There is also the routine case: an instance that
            // already ran today's report raises a ConflictException, which is the system working.
            log.error("scheduled sales report for {} did not complete: {}", yesterday,
                    e.getMessage());
        }
    }
}

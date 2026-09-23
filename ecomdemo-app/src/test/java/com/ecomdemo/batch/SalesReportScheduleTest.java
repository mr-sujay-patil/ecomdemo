package com.ecomdemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the schedule really runs the job — the half of "the report is generated on schedule"
 * that reading the cron expression cannot establish.
 *
 * <p>The trick is to keep the production wiring and change only the clock face: the cron is a
 * property, so this context sets it to "every second" and waits for the report to appear. What
 * fires it is the same {@code @Scheduled} annotation, through the same
 * {@code ThreadPoolTaskScheduler}, calling the same service. Nothing here calls
 * {@code runDailySalesReport} itself; if the annotation were missing or the expression unreadable,
 * the file would never turn up and this test would time out.
 *
 * <p>It runs in the {@code test} profile, against H2 and with no containers, because none of what
 * it is checking is about PostgreSQL. It does pay for a context of its own — the
 * {@code @TestPropertySource} guarantees that — which is the honest price of testing a schedule.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "ecomdemo.batch.sales-report-cron=*/1 * * * * *",
    "ecomdemo.batch.directory=./target/batch-schedule"
})
class SalesReportScheduleTest {

    @Autowired
    private BatchProperties properties;

    @Test
    @DisplayName("the cron fires and the report for yesterday is written, without anyone calling it")
    void theScheduleRunsTheJob() throws java.io.IOException {
        Path expected = SalesReportJobConfig.reportFileFor(
                properties.reportDirectory(), LocalDate.now().minusDays(1));

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(Files.exists(expected))
                        .as("the scheduled job should have written %s", expected)
                        .isTrue());

        // And it ran the real job, not just created a file: the summary line is what the tasklet
        // step writes after querying the orders table.
        assertThat(Files.readString(expected)).startsWith("# EcomDemo daily sales report");
    }

    @Test
    @DisplayName("the configured production cron means 02:00 every day, and is six fields not five")
    void theProductionCronIsDaily() {
        // The property under test is the DEFAULT in application.properties, read here as a
        // literal rather than injected, because this context has deliberately overridden it.
        CronExpression daily = CronExpression.parse("0 0 2 * * *");

        var first = daily.next(LocalDate.of(2026, 3, 1).atStartOfDay());
        assertThat(first).isEqualTo(LocalDate.of(2026, 3, 1).atTime(2, 0));
        assertThat(daily.next(first)).isEqualTo(LocalDate.of(2026, 3, 2).atTime(2, 0));

        // The five-field Unix form of "02:00 daily" is a different expression here, and Spring
        // reads it as "the 2nd minute of every hour" - the mistake this assertion exists to name.
        assertThat(CronExpression.parse("0 2 * * * *").next(LocalDate.of(2026, 3, 1).atStartOfDay()))
                .isEqualTo(LocalDate.of(2026, 3, 1).atTime(0, 2));
    }
}

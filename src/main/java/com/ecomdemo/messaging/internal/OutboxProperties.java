package com.ecomdemo.messaging.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The outbox's settings, bound from {@code ecomdemo.outbox.*}.
 *
 * <p>A record with defaults in the canonical constructor, the same shape as
 * {@code BatchProperties}: the feature works with no configuration at all, and an operator can
 * change the pace of the relay without a rebuild.
 *
 * @param pollDelay how long the relay waits after finishing one batch before starting the next
 * @param batchSize how many pending events one relay tick publishes
 * @param retention how long a published event is kept before the cleanup job sweeps it
 * @param cleanupCron six-field cron for the cleanup job, or {@code -} to disable it
 */
@ConfigurationProperties(prefix = "ecomdemo.outbox")
public record OutboxProperties(
        Duration pollDelay, int batchSize, Duration retention, String cleanupCron) {

    public OutboxProperties {
        // One second. This is the outbox's latency floor for the common case, and the number is a
        // trade rather than a tuning: shorter means a notification arrives sooner and the database
        // is asked "anything pending?" more often; longer means fewer queries and a visibly
        // laggy notification. A second is below what a person waiting for an email would notice
        // and is a query an indexed empty-set lookup answers in microseconds.
        pollDelay = pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()
                ? Duration.ofSeconds(1)
                : pollDelay;

        // Small, because a batch is one transaction and one transaction should be short. This is
        // also the recovery rate: after an outage of N orders the backlog drains at batchSize per
        // pollDelay, which at these defaults is 100 events a second — fast enough that a ten
        // minute outage clears in seconds, bounded enough that recovery never becomes one giant
        // transaction holding locks while it goes.
        batchSize = batchSize <= 0 ? 100 : batchSize;

        // Seven days. Long enough that a published event is still there on Monday for whoever is
        // asking what happened on Friday; short enough that the table stays small. The window is
        // for FORENSICS, not for correctness — nothing re-reads a published row — which is why it
        // can be measured in days rather than argued about.
        retention = retention == null || retention.isNegative() ? Duration.ofDays(7) : retention;

        // 03:00, an hour after the sales report, so the two never contend for the same database at
        // the same time on a laptop that has one core to spare. Six fields: Spring's cron starts
        // at SECONDS.
        cleanupCron = cleanupCron == null || cleanupCron.isBlank() ? "0 0 3 * * *" : cleanupCron;
    }
}

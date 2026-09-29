package com.ecomdemo.outbox.internal;

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
 * @param maxBatchesPerTick how many FULL batches one tick may publish back to back before it
 *     yields to the scheduler (Phase 30)
 */
@ConfigurationProperties(prefix = "ecomdemo.outbox")
public record OutboxProperties(
        Duration pollDelay, int batchSize, Duration retention, String cleanupCron, int maxBatchesPerTick) {

    public OutboxProperties {
        // One second. This is the outbox's latency floor for the common case, and the number is a
        // trade rather than a tuning: shorter means a notification arrives sooner and the database
        // is asked "anything pending?" more often; longer means fewer queries and a visibly
        // laggy notification. A second is below what a person waiting for an email would notice
        // and is a query an indexed empty-set lookup answers in microseconds.
        pollDelay = pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()
                ? Duration.ofSeconds(1)
                : pollDelay;

        // Small, because a batch is one transaction and one transaction should be short, and
        // bounded so that recovery never becomes one giant transaction holding locks while it goes.
        batchSize = batchSize <= 0 ? 100 : batchSize;

        // Twenty. Until Phase 30 a tick published ONE batch and then slept pollDelay, so the relay
        // could never move more than batchSize events per (batch time + pollDelay): measured under
        // load, 100 events in ~0.22 s then 1 s asleep, about 82 events a second - two per order,
        // so a ceiling of ~41 orders a second, and every order past it waited in the table. Now a
        // tick keeps going while batches come back FULL (a full batch means more is waiting) and
        // stops at the first short one. The cap exists because the relay shares the scheduler's
        // single thread with the cleanup job and the sales report: 20 batches is a few seconds of
        // work at most before the others get a turn. Each batch is still its own transaction.
        maxBatchesPerTick = maxBatchesPerTick <= 0 ? 20 : maxBatchesPerTick;

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

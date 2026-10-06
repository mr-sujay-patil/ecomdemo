package com.ecomdemo.outbox.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer that drives the outbox.
 *
 * <p>Deliberately almost empty: it owns the schedule and nothing else, and every decision about
 * what a tick does lives in {@link OutboxBatchPublisher}. See that class for why the two are
 * separate beans rather than one method carrying both annotations.
 *
 * <h2>{@code fixedDelay}, not {@code fixedRate}</h2>
 *
 * <p>{@code fixedRate} starts a run every N milliseconds regardless of whether the previous one
 * has finished; {@code fixedDelay} waits N milliseconds <em>after</em> one finishes before
 * starting the next. With a broker that is down, a tick takes as long as one failed send — several
 * seconds — and {@code fixedRate} would queue up runs faster than they complete, piling
 * overlapping relays onto a database that is already struggling to be useful. {@code fixedDelay}
 * self-throttles: the worse things are, the less often it tries.
 *
 * <h2>What the drain rate actually is</h2>
 *
 * <p>Until Phase 30 a tick published one batch and slept, and this comment claimed that drained
 * "about a hundred events a second". The load test measured it: a batch of 100 takes ~0.22 s
 * (every send waits for its acknowledgement), then the relay slept a full second while the table
 * kept filling - about 82 events a second, a ceiling of ~41 orders a second, and past it every
 * order waited longer than the one before (see {@code docs/performance.md}).
 *
 * <p>Now a tick keeps publishing while batches come back FULL, because a full batch means more is
 * waiting, and sleeps only after a short one - or after {@code maxBatchesPerTick}, so the one
 * scheduler thread this shares with the cleanup job and the sales report is never held for long.
 * An idle outbox behaves exactly as before: one empty query per {@code pollDelay}. Each batch
 * still commits on its own, so a crash halfway through a drain keeps the progress made.
 *
 * <h2>Many instances, one relay at a time</h2>
 *
 * <p>This is a timer inside this JVM, and a service can run several instances (catalog-service
 * runs two to four pods behind its autoscaler). Until KI-002 every instance's relay polled the
 * same table and nothing stopped two of them reading the same pending row and both publishing it:
 * duplicate sends, absorbed by {@code processed_event} on the consumer side, but waste, and
 * noise.
 *
 * <p>Now each batch starts by taking a PostgreSQL transaction-level advisory lock
 * ({@link OutboxEventRepository#tryLockRelay}). The relay that gets it publishes the batch; any
 * other one finds the lock taken, publishes nothing, and tries again next tick. The lock is
 * released by the commit that marks the batch, by a rollback, or by the death of the connection,
 * so a crashed instance cannot leave the others locked out.
 *
 * <p>Not {@code SELECT ... FOR UPDATE SKIP LOCKED}, which the first version of this note proposed,
 * because that hands each relay a DISJOINT set of rows, and the relays then publish concurrently:
 * a relay that failed on event N rolls it back while another has already published N+1, and two
 * events of one order reach the topic in the wrong order. The publisher stops its batch at the
 * first failure for exactly that reason. A single holder keeps the order, and costs nothing that
 * was being gained: the relay's throughput is bounded by the broker's acknowledgements, not by
 * how many relays ask. The one remaining duplicate is the documented crash seam (sent, process
 * died before the commit), which {@code processed_event} absorbs as before.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxBatchPublisher publisher;
    private final OutboxProperties properties;

    OutboxRelay(OutboxBatchPublisher publisher, OutboxProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * {@code initialDelay} so the first tick does not race the application's own startup — Flyway,
     * the connection pool and the producer's metadata fetch all want the first second more than
     * an empty outbox poll does.
     */
    @Scheduled(
            fixedDelayString = "${ecomdemo.outbox.poll-delay:1s}",
            initialDelayString = "${ecomdemo.outbox.poll-delay:1s}")
    void relay() {
        int published = 0;
        try {
            for (int batch = 1; batch <= properties.maxBatchesPerTick(); batch++) {
                int thisBatch = publisher.publishPendingBatch();
                published += thisBatch;
                // Short means the table is drained, or a send failed and the publisher stopped at
                // it; either way, wait for the next tick rather than spin.
                if (thisBatch < properties.batchSize()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws kills only its own run — the next tick still fires —
            // but the framework would log it without saying what it was doing. The batch's own
            // failures are already handled and logged inside the publisher; reaching here means
            // something structural, such as the database being unreachable.
            log.error("Outbox relay tick failed: {}", e.getMessage(), e);
        }
        if (published > 0) {
            log.info("Outbox relay published {} event(s)", published);
        }
    }
}

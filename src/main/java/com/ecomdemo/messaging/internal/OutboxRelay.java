package com.ecomdemo.messaging.internal;

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
 * <p>One batch per tick, so at the defaults — 100 events, one second apart — the outbox drains at
 * about a hundred events a second once the broker returns. A ten-minute outage during which an
 * order was placed every second clears in six seconds. Not draining the whole backlog inside one
 * tick is the point: each batch commits on its own, so recovery is incremental and its progress
 * survives a crash halfway through.
 *
 * <h2>One instance, and what happens with two</h2>
 *
 * <p>This is a timer inside this JVM, like {@code SalesReportScheduler}: run two instances of the
 * application and both relays poll the same table. Two relays can read the same pending row and
 * both publish it, because nothing here takes a lock.
 *
 * <p>That is survivable rather than broken, and only because of the work Phase 17 did first: the
 * duplicate carries the same {@code event_id}, and {@code processed_event} on the consumer side
 * refuses it. The cost of running two relays is duplicate <em>sends</em>, not duplicate
 * notifications. The proper fix is {@code SELECT ... FOR UPDATE SKIP LOCKED}, which hands each
 * relay a disjoint set of rows; it is left out here because it is a PostgreSQL-flavoured query
 * that the H2 unit suite could not run, and because a lock is the wrong thing to introduce in the
 * phase whose subject is durability. It is recorded in {@code docs/decisions.md} as deferred, not
 * as solved.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxBatchPublisher publisher;

    OutboxRelay(OutboxBatchPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * {@code initialDelay} so the first tick does not race the application's own startup — Flyway,
     * the connection pool and the producer's metadata fetch all want the first second more than
     * an empty outbox poll does.
     */
    @Scheduled(
            fixedDelayString = "${ecomdemo.outbox.poll-delay}",
            initialDelayString = "${ecomdemo.outbox.poll-delay}")
    void relay() {
        try {
            int published = publisher.publishPendingBatch();
            if (published > 0) {
                log.info("Outbox relay published {} event(s)", published);
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws kills only its own run — the next tick still fires —
            // but the framework would log it without saying what it was doing. The batch's own
            // failures are already handled and logged inside the publisher; reaching here means
            // something structural, such as the database being unreachable.
            log.error("Outbox relay tick failed: {}", e.getMessage(), e);
        }
    }
}

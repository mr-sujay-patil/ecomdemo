package com.ecomdemo.outbox.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The outbox's backlog, as two gauges (KI-031). Until these, nothing watched the outbox: a relay
 * that could not send (Kafka down, an ACL refusal, a row that always fails) let events pile up in
 * the table with nothing raising it.
 *
 * <ul>
 *   <li>{@code outbox.events.pending}: how many events wait, as the last check counted them.
 *   <li>{@code outbox.events.oldest.pending.age} (seconds): how long the oldest of them has waited.
 *       A healthy relay publishes within about a second, so this is the alerting signal
 *       ({@code OutboxEventsStuck} in {@code docker/prometheus/alerts.yml}); the count only says
 *       how big the backlog is, and it rises with traffic as well as with failure.
 * </ul>
 *
 * <p>The values are stored by {@link OutboxBacklogCheck}, not queried on every scrape, as
 * {@code SagaMetrics} does: a scrape that ran a query would hang on a database outage and lose
 * every other metric of that scrape with it. The age is computed when it is READ, from the oldest
 * {@code created_at} the last check saw, so it keeps growing while events stay stuck even when the
 * check can no longer reach the database.
 *
 * <p>Every instance of a service reports the same database's backlog, so a query over several
 * instances takes {@code max}, not {@code sum}.
 */
@Component
public class OutboxMetrics {

    static final String PENDING = "outbox.events.pending";
    static final String OLDEST_PENDING_AGE = "outbox.events.oldest.pending.age";

    private final Clock clock;
    private final AtomicReference<OutboxBacklog> last = new AtomicReference<>(new OutboxBacklog(0, null));

    /** Public so the monitoring tests can build the real meters into their own registry. */
    @Autowired
    public OutboxMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    OutboxMetrics(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        Gauge.builder(PENDING, last, backlog -> backlog.get().pending())
                .description("Outbox events not yet published, at the last backlog check")
                .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE, this, OutboxMetrics::oldestPendingAgeSeconds)
                .description("How long the oldest unpublished outbox event has waited")
                .baseUnit("seconds")
                .register(registry);
    }

    void backlog(OutboxBacklog backlog) {
        last.set(backlog);
    }

    private double oldestPendingAgeSeconds() {
        Instant oldest = last.get().oldest();
        if (oldest == null) {
            return 0;
        }
        // Never negative: created_at is written by whichever instance appended the event, and its
        // clock may run a little ahead of this one's.
        return Math.max(0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }
}

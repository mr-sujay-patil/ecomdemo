package com.ecomdemo.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KI-031: the outbox's two gauges, read the way Prometheus reads them.
 *
 * <p>The age is the alerting signal: a healthy relay publishes within about a second, so an event
 * that has waited minutes means the relay cannot send. It is computed when it is READ, from the
 * oldest pending row's {@code created_at} the last check saw, so it keeps growing while events
 * stay stuck even if the check itself can no longer reach the database.
 */
@DisplayName("OutboxMetrics")
class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T07:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOW);
    private final OutboxMetrics metrics = new OutboxMetrics(registry, clock);

    private double gauge(String name) {
        Gauge gauge = registry.find(name).gauge();
        assertThat(gauge).as(name + " is registered").isNotNull();
        return gauge.value();
    }

    @Test
    @DisplayName("registers the pending count and the oldest pending event's age, in seconds")
    void registersBothGauges() {
        assertThat(registry.find(OutboxMetrics.PENDING).gauge()).isNotNull();
        Gauge age = registry.find(OutboxMetrics.OLDEST_PENDING_AGE).gauge();
        assertThat(age).isNotNull();
        assertThat(age.getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    @DisplayName("an empty outbox (and one never checked yet) reads 0 and 0, not a missing series")
    void emptyIsZero() {
        assertThat(gauge(OutboxMetrics.PENDING)).isZero();
        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE)).isZero();

        metrics.backlog(new OutboxBacklog(0, null));

        assertThat(gauge(OutboxMetrics.PENDING)).isZero();
        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE)).isZero();
    }

    @Test
    @DisplayName("reports what the last check found: how many wait, and for how long the oldest has")
    void reportsTheBacklog() {
        metrics.backlog(new OutboxBacklog(42, NOW.minusSeconds(90)));

        assertThat(gauge(OutboxMetrics.PENDING)).isEqualTo(42);
        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE)).isEqualTo(90);
    }

    @Test
    @DisplayName("the age keeps growing between checks: a stuck event is not hidden by a check that stopped")
    void theAgeIsComputedWhenRead() {
        metrics.backlog(new OutboxBacklog(3, NOW.minusSeconds(10)));

        clock.advance(Duration.ofMinutes(5));

        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE)).isEqualTo(310);
    }

    @Test
    @DisplayName("a drained outbox goes back to 0")
    void drainedIsZeroAgain() {
        metrics.backlog(new OutboxBacklog(3, NOW.minusSeconds(600)));
        metrics.backlog(new OutboxBacklog(0, null));

        assertThat(gauge(OutboxMetrics.PENDING)).isZero();
        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE)).isZero();
    }

    @Test
    @DisplayName("a created_at slightly ahead of this clock (another host's) reads 0, never negative")
    void neverNegative() {
        metrics.backlog(new OutboxBacklog(1, NOW.plusSeconds(2)));

        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE)).isZero();
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

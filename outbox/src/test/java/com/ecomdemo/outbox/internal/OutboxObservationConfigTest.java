package com.ecomdemo.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * That only the relay's tick is dropped.
 *
 * <p>Runs the predicate inside a real {@link ObservationRegistry}, the way Boot installs it, rather
 * than calling it directly: a no-op observation is what the scheduler would actually get.
 */
@DisplayName("OutboxObservationConfig")
class OutboxObservationConfigTest {

    private static final String NAME = "tasks.scheduled.execution";

    private final ObservationRegistry registry = ObservationRegistry.create();

    @BeforeEach
    void installThePredicate() {
        registry.observationConfig()
                .observationHandler(new AcceptEverything())
                .observationPredicate(new OutboxObservationConfig().noOutboxRelayObservations());
    }

    @Test
    @DisplayName("drops the outbox relay's scheduled tick")
    void dropsTheRelay() {
        assertThat(observe(OutboxRelay.class).isNoop()).isTrue();
    }

    @Test
    @DisplayName("keeps the outbox cleanup sweep")
    void keepsTheCleanupJob() {
        assertThat(observe(OutboxCleanupJob.class).isNoop()).isFalse();
    }

    @Test
    @DisplayName("keeps the service's own scheduled jobs, such as the app's nightly sales report")
    void keepsTheServicesOwnJobs() {
        // In ecomdemo-app this was the real SalesReportScheduler. The library cannot see any
        // service's classes (Phase 24), so a stand-in plays its part: any @Scheduled job that is
        // not the relay must still be traced - the regression ultrareview caught in Phase 23 was
        // a predicate that hid ALL of them.
        assertThat(observe(NightlyReport.class).isNoop()).isFalse();
    }

    @Test
    @DisplayName("keeps observations that are not scheduled tasks")
    void keepsEverythingElse() {
        assertThat(Observation.createNotStarted("http.server.requests", registry).isNoop())
                .isFalse();
    }

    /** The context Spring's scheduler builds for a {@code @Scheduled} method of {@code type}. */
    private Observation observe(Class<?> type) {
        Method scheduled =
                Arrays.stream(type.getDeclaredMethods())
                        .filter(m -> m.isAnnotationPresent(Scheduled.class))
                        .findFirst()
                        .orElseThrow();
        // Spring builds this from the bean instance; the predicate only reads its class, so the
        // test supplies the class directly instead of constructing each job and its dependencies.
        ScheduledTaskObservationContext context =
                new ScheduledTaskObservationContext(new Object(), scheduled) {
                    @Override
                    public Class<?> getTargetClass() {
                        return type;
                    }
                };
        return Observation.createNotStarted(NAME, () -> context, registry);
    }

    /** A service's own scheduled job, standing in for the app's sales report. */
    static final class NightlyReport {
        @Scheduled(cron = "0 0 2 * * *")
        void run() {
        }
    }

    /** Without a handler every observation is a no-op, and the test would prove nothing. */
    private static final class AcceptEverything implements ObservationHandler<Observation.Context> {
        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}

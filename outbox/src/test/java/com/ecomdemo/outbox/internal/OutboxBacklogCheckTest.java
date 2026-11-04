package com.ecomdemo.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * KI-031: the check that feeds {@link OutboxMetrics}.
 *
 * <p>Which rows {@code backlog()} counts is SQL, tested against a database in
 * {@code OutboxEventRepositoryTest} (ecomdemo-app). Here: that the check passes the answer on, and
 * that a failed check leaves the last answer standing instead of reporting an empty outbox.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxBacklogCheck")
class OutboxBacklogCheckTest {

    @Mock
    private OutboxEventRepository outbox;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry);

    private double pending() {
        return registry.find(OutboxMetrics.PENDING).gauge().value();
    }

    @Test
    @DisplayName("passes what the outbox holds to the gauges")
    void reportsTheBacklog() {
        when(outbox.backlog()).thenReturn(new OutboxBacklog(7, Instant.now().minusSeconds(30)));

        new OutboxBacklogCheck(outbox, metrics).check();

        assertThat(pending()).isEqualTo(7);
        assertThat(registry.find(OutboxMetrics.OLDEST_PENDING_AGE).gauge().value()).isBetween(29.0, 40.0);
    }

    @Test
    @DisplayName("a check that cannot reach the database keeps the last values: an outage is not an empty outbox")
    void aFailedCheckKeepsTheLastValues() {
        when(outbox.backlog())
                .thenReturn(new OutboxBacklog(7, Instant.now().minusSeconds(30)))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        OutboxBacklogCheck check = new OutboxBacklogCheck(outbox, metrics);
        check.check();

        assertThatCode(check::check).doesNotThrowAnyException();

        assertThat(pending()).isEqualTo(7);
    }

    @Test
    @DisplayName("runs on its own schedule, not on the relay's one-second tick, so an idle relay stays one query")
    void hasItsOwnSchedule() throws Exception {
        Method check = OutboxBacklogCheck.class.getDeclaredMethod("check");
        Scheduled scheduled = check.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${ecomdemo.outbox.backlog-check-delay:15s}");
    }
}

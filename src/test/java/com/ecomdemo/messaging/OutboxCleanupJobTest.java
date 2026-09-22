package com.ecomdemo.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * That the sweep asks for the right cutoff.
 *
 * <p>Which rows the cutoff then matches is the repository's SQL and is tested against a database
 * in {@code OutboxEventRepositoryTest} — including the case that matters most, that a pending row
 * is never swept however old it is.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxCleanupJob")
class OutboxCleanupJobTest {

    @Mock
    private OutboxEventRepository outbox;

    @Test
    @DisplayName("sweeps everything published longer ago than the retention window")
    void usesTheRetentionWindowAsTheCutoff() {
        OutboxCleanupJob job =
                new OutboxCleanupJob(
                        outbox,
                        new OutboxProperties(
                                Duration.ofSeconds(1), 100, Duration.ofDays(7), "0 0 3 * * *"));
        when(outbox.deletePublishedBefore(any(Instant.class))).thenReturn(3);

        Instant before = Instant.now();
        job.sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).deletePublishedBefore(cutoff.capture());

        // Seven days back from now, give or take the microseconds this test took to run. Asserted
        // as a window rather than an equality because the job reads the clock itself, and a test
        // that pinned the instant would be testing a mock of Instant.now() rather than the job.
        assertThat(cutoff.getValue())
                .isBetween(before.minus(Duration.ofDays(7)).minusSeconds(5),
                        Instant.now().minus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("a longer retention keeps more, which is the only knob this job has")
    void theWindowIsTheOnlyThingThatMoves() {
        OutboxCleanupJob job =
                new OutboxCleanupJob(
                        outbox,
                        new OutboxProperties(
                                Duration.ofSeconds(1), 100, Duration.ofDays(30), "0 0 3 * * *"));
        when(outbox.deletePublishedBefore(any(Instant.class))).thenReturn(0);

        job.sweep();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).deletePublishedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(Instant.now().minus(Duration.ofDays(29)));
    }
}

package com.ecomdemo.outbox.internal;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * How many batches one tick publishes (Phase 30).
 *
 * <p>The load test found the relay's ceiling: one batch per tick, then a full {@code pollDelay}
 * asleep however much was waiting. These pin the fix - keep going while batches come back full,
 * stop at the first short one, never more than the cap - with the publisher mocked, because the
 * claim is about the loop, not about Kafka.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxRelay")
class OutboxRelayTest {

    private static final int BATCH = 100;

    @Mock
    private OutboxBatchPublisher publisher;

    private OutboxRelay relay(int maxBatchesPerTick) {
        return new OutboxRelay(
                publisher,
                new OutboxProperties(Duration.ofSeconds(1), BATCH, Duration.ofDays(7), "-", maxBatchesPerTick));
    }

    @Test
    @DisplayName("an idle outbox costs one query per tick, as before")
    void anEmptyOutboxIsOneQuery() {
        when(publisher.publishPendingBatch()).thenReturn(0);

        relay(20).relay();

        verify(publisher, times(1)).publishPendingBatch();
    }

    @Test
    @DisplayName("a backlog is drained in one tick while batches come back full")
    void keepsGoingWhileBatchesAreFull() {
        when(publisher.publishPendingBatch()).thenReturn(BATCH, BATCH, 37);

        relay(20).relay();

        verify(publisher, times(3)).publishPendingBatch();
    }

    @Test
    @DisplayName("a failed send (a short batch) ends the tick instead of retrying at once")
    void stopsAtAShortBatch() {
        // The publisher stops at the first event it cannot send and returns what it managed.
        when(publisher.publishPendingBatch()).thenReturn(BATCH, 12);

        relay(20).relay();

        verify(publisher, times(2)).publishPendingBatch();
    }

    @Test
    @DisplayName("never more than maxBatchesPerTick, so the shared scheduler thread is released")
    void isCapped() {
        when(publisher.publishPendingBatch()).thenReturn(BATCH);

        relay(5).relay();

        verify(publisher, times(5)).publishPendingBatch();
    }

    @Test
    @DisplayName("a structural failure ends the tick; the next tick tries again")
    void swallowsAStructuralFailure() {
        when(publisher.publishPendingBatch()).thenThrow(new IllegalStateException("database down"));

        relay(20).relay();

        verify(publisher, times(1)).publishPendingBatch();
    }
}

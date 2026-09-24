package com.ecomdemo.messaging.internal;

import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.KafkaTopics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.support.SendResult;

/**
 * What one relay tick does, and — more importantly — what it does when the send fails.
 *
 * <p>The broker is mocked here on purpose. The claims being tested are about the RELAY's
 * bookkeeping: which rows get marked, which stay pending, and where a failing batch stops. Those
 * are decisions this class makes, and a real broker would only make them harder to provoke —
 * "make the third send fail and the first two succeed" is a one-line stub and a small ordeal to
 * arrange against a live cluster. The end-to-end claim, over a real broker, is
 * {@code OutboxRelayKafkaIT}; the real outage is in {@code scripts/smoke-test.sh}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxBatchPublisher")
class OutboxBatchPublisherTest {

    @Mock
    private OutboxEventRepository outbox;

    @Mock
    private OutboxKafkaSender sender;

    private OutboxBatchPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher =
                new OutboxBatchPublisher(
                        outbox,
                        sender,
                        new OutboxProperties(Duration.ofSeconds(1), 10, Duration.ofDays(7), "-"));
    }

    private static OutboxEvent orderEvent(String orderId) {
        return new OutboxEvent(
                UUID.randomUUID(),
                OutboxWriter.ORDER_AGGREGATE,
                orderId,
                OrderPlacedEvent.class.getSimpleName(),
                "{\"orderId\":" + orderId + "}");
    }

    /**
     * A completed future carrying null. The publisher waits on the future and ignores its value —
     * it needs the ACKNOWLEDGEMENT, not the metadata — so null is the honest stub rather than a
     * mock result pretending to be consulted.
     */
    private void sendSucceeds() {
        when(sender.send(anyString(), anyString(), anyString()))
                .thenReturn(completed());
    }

    private static CompletableFuture<SendResult<String, String>> completed() {
        return CompletableFuture.completedFuture(null);
    }

    private void sendFailsWith(String message) {
        when(sender.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(message)));
    }

    @Nested
    @DisplayName("when the broker is healthy")
    class WhenHealthy {

        @Test
        void marksEveryEventPublished() {
            OutboxEvent first = orderEvent("1");
            OutboxEvent second = orderEvent("2");
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of(first, second));
            sendSucceeds();

            assertThat(publisher.publishPendingBatch()).isEqualTo(2);

            assertThat(first.isPublished()).isTrue();
            assertThat(second.isPublished()).isTrue();
            assertThat(first.getAttempts()).isZero();
        }

        @Test
        @DisplayName("keys by the aggregate id, so one order's events share a partition")
        void keysByTheOrderId() {
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of(orderEvent("4812")));
            sendSucceeds();

            publisher.publishPendingBatch();

            verify(sender).send(eq(KafkaTopics.ORDERS_PLACED), eq("4812"), anyString());
        }

        @Test
        @DisplayName("sends the committed bytes, never a re-serialisation of them")
        void sendsThePayloadVerbatim() {
            OutboxEvent event = orderEvent("7");
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of(event));
            sendSucceeds();

            publisher.publishPendingBatch();

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(sender).send(anyString(), anyString(), payload.capture());
            // Byte-for-byte what the transaction committed. If the relay ever deserialised and
            // re-serialised the payload, this would still pass for a simple record and would
            // quietly start failing the day a field's representation changed - so the assertion
            // is on identity of content, and the design that guarantees it is the StringSerializer
            // template in OutboxPublisherConfig.
            assertThat(payload.getValue()).isEqualTo(event.getPayload());
        }

        @Test
        void doesNothingAndTouchesNoBrokerWhenTheOutboxIsEmpty() {
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of());

            assertThat(publisher.publishPendingBatch()).isZero();

            verify(sender, never()).send(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("when the broker is unreachable")
    class WhenBrokerIsDown {

        /** THE PHASE'S "DONE WHEN", as a unit test: a failed send loses nothing. */
        @Test
        @DisplayName("leaves the event pending, so nothing is lost")
        void leavesTheEventPending() {
            OutboxEvent event = orderEvent("1");
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of(event));
            sendFailsWith("Broker may not be available");

            assertThat(publisher.publishPendingBatch()).isZero();

            assertThat(event.isPublished()).isFalse();
            assertThat(event.getPublishedAt()).isNull();
        }

        @Test
        @DisplayName("counts the attempt and records why, so a stuck row is visible")
        void recordsTheAttemptAndTheReason() {
            OutboxEvent event = orderEvent("1");
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of(event));
            sendFailsWith("Broker may not be available");

            publisher.publishPendingBatch();

            assertThat(event.getAttempts()).isEqualTo(1);
            assertThat(event.getLastError()).contains("Broker may not be available");
        }

        /**
         * The batch stops at the first failure, and this is the test that says why it must.
         *
         * <p>If the relay carried on, event 2 would be published while event 1 was still pending —
         * so the topic would carry them in the wrong order, and event 1 would arrive later, after
         * the event that logically follows it. An outbox that reorders under failure has thrown
         * away the guarantee that made it worth building.
         *
         * <p>The second reason is cost: a failure here means the broker is unreachable, which is
         * not a property of this event. Continuing would spend {@code max.block.ms} on every
         * remaining row of the batch.
         */
        @Test
        @DisplayName("stops the batch, rather than reordering the topic behind the failed event")
        void stopsAtTheFirstFailure() {
            OutboxEvent first = orderEvent("1");
            OutboxEvent second = orderEvent("2");
            OutboxEvent third = orderEvent("3");
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of(first, second, third));
            sendFailsWith("Broker may not be available");

            publisher.publishPendingBatch();

            assertThat(first.getAttempts()).isEqualTo(1);
            assertThat(second.getAttempts()).as("never attempted").isZero();
            assertThat(third.getAttempts()).as("never attempted").isZero();
            verify(sender).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("keeps the events published before the failure, and commits that progress")
        void keepsEarlierSuccesses() {
            OutboxEvent first = orderEvent("1");
            OutboxEvent second = orderEvent("2");
            when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                    .thenReturn(List.of(first, second));
            when(sender.send(anyString(), eq("1"), anyString())).thenReturn(completed());
            when(sender.send(anyString(), eq("2"), anyString()))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("gone")));

            assertThat(publisher.publishPendingBatch()).isEqualTo(1);

            // Partial progress is real progress: the first event is done and will not be sent
            // again, and the transaction commits that fact along with the second one's raised
            // attempt count. Recovery is incremental precisely because a half-finished batch is
            // still a finished batch.
            assertThat(first.isPublished()).isTrue();
            assertThat(second.isPublished()).isFalse();
        }
    }

    @Test
    @DisplayName("an unmapped event type fails loudly rather than sitting pending for ever")
    void refusesAnUnroutableEvent() {
        OutboxEvent unknown =
                new OutboxEvent(UUID.randomUUID(), "Order", "1", "SomethingElseHappened", "{}");
        when(outbox.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                .thenReturn(List.of(unknown));

        // Caught by the batch's own handler, like any other failure: the row stays pending with
        // its reason recorded. What must NOT happen is that it is silently skipped - it would
        // block every event behind it for ever while nothing said so.
        publisher.publishPendingBatch();

        assertThat(unknown.isPublished()).isFalse();
        assertThat(unknown.getLastError()).contains("SomethingElseHappened");
    }

    @Test
    @DisplayName("the payload column is 1000 characters and an error message is not")
    void truncatesAnOverlongError() {
        OutboxEvent event = orderEvent("1");

        event.markFailed("x".repeat(5000));

        assertThat(event.getLastError()).hasSize(1000);
    }

    @Test
    @DisplayName("publishing after a failure clears the recorded error")
    void clearsTheErrorOnceItSucceeds() {
        OutboxEvent event = orderEvent("1");
        event.markFailed("Broker may not be available");

        event.markPublished();

        // A stale error beside a published_at would read as "this one failed", which is the
        // opposite of what happened, and is exactly the sort of thing that wastes an hour at
        // three in the morning.
        assertThat(event.getLastError()).isNull();
        assertThat(event.isPublished()).isTrue();
    }
}

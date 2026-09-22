package com.ecomdemo.messaging.internal;

import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.messaging.OrderPlacedEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.JacksonUtils;

/**
 * What the outbox row actually contains.
 *
 * <p>The writer builds its own mapper — {@link JacksonUtils#enhancedObjectMapper()}, the Kafka
 * consumer's — and this test reads the payload back with the same one. That round trip is the
 * point: the payload's FORMAT is what is under test, and a mismatch between the mapper that writes
 * it and the mapper that reads it is the one failure mode nothing else in the build would catch
 * before run time.
 *
 * <p>That the append joins the caller's transaction is a claim about Spring's proxy and is proved
 * in {@code OutboxRelayKafkaIT}, where there is a real transaction to join and a real one to be
 * refused for lack of.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxWriter")
class OutboxWriterTest {

    @Mock
    private OutboxEventRepository outbox;

    private OutboxWriter writer;

    @BeforeEach
    void setUp() {
        // Constructed here and not in a field initialiser: field initialisers run before Mockito
        // populates @Mock, so the writer would be built around a null repository.
        writer = new OutboxWriter(outbox);
    }

    private static final OrderPlacedEvent EVENT =
            OrderPlacedEvent.of(4812L, "shopper", new BigDecimal("1299.50"), 3, Instant.now());

    private OutboxEvent append() {
        writer.append(EVENT);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("carries the event's own id, not a new one")
    void reusesTheEventId() {
        // The id is generated once, by the producer, and is what the consumer's processed_event
        // table matches on. Generating a fresh one here would make every republication a distinct
        // event and would recognise nothing.
        assertThat(append().getEventId()).isEqualTo(EVENT.eventId());
    }

    @Test
    @DisplayName("keys the row by the order id, which becomes the partition key")
    void recordsTheAggregate() {
        OutboxEvent row = append();

        assertThat(row.getAggregateType()).isEqualTo("Order");
        assertThat(row.getAggregateId()).isEqualTo("4812");
        assertThat(row.getEventType()).isEqualTo("OrderPlacedEvent");
    }

    @Test
    @DisplayName("is born pending, with no attempts and no error")
    void startsPending() {
        OutboxEvent row = append();

        assertThat(row.isPublished()).isFalse();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("freezes the whole event as JSON the consumer can read back")
    void serialisesTheEvent() throws Exception {
        String payload = append().getPayload();

        // Round-tripped through the same mapper the consumer's JsonDeserializer uses. This is the
        // assertion that would have caught a mapper mismatch - Boot 4's Jackson 3 writes an
        // Instant differently from spring-kafka's Jackson 2, and nothing else in the build would
        // notice until a message failed to deserialise on the broker.
        OrderPlacedEvent readBack =
                JacksonUtils.enhancedObjectMapper().readValue(payload, OrderPlacedEvent.class);

        assertThat(readBack).isEqualTo(EVENT);
    }
}

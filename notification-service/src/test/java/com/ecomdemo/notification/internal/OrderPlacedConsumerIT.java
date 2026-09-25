package com.ecomdemo.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.support.KafkaContainerConfig;
import com.ecomdemo.support.PostgresContainerConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * What this service does with a message on {@code orders.placed}.
 *
 * <h2>This replaced an end-to-end test, and the replacement is narrower on purpose</h2>
 *
 * <p>It was {@code OrderPlacedKafkaIT}, which placed a real order through the application's
 * {@code /api/orders} and waited for a notification row. That spanned the producer and the consumer,
 * which was the right shape while both were one deployable and is impossible now — order-service
 * publishes, this service consumes, and no single-module test can stand up both.
 *
 * <p>So the claims were split by which side owns them:
 *
 * <ul>
 *   <li>The three CONSUMER claims are here, driven by publishing to the topic directly: one event
 *       makes one notification, a redelivery makes no second one, and a poison message does not block
 *       the partition behind it.
 *   <li>The one PRODUCER claim — "a rejected checkout publishes nothing, because the transaction never
 *       committed" — belongs to the application, where the outbox lives. It is about a transaction
 *       rolling back, and there is no message for this service to see.
 *   <li>The end-to-end path, order → Kafka → notification, is the smoke test's, which already asserts
 *       it survives the broker being stopped and restarted.
 * </ul>
 *
 * <p>Publishing directly is not a weaker test of this service; it is a more precise one. The old
 * version could fail because checkout broke, which said nothing about the consumer.
 */
@SpringBootTest
@Import({PostgresContainerConfig.class, KafkaContainerConfig.class})
@ActiveProfiles("it")
@DisplayName("orders.placed -> notification")
class OrderPlacedConsumerIT {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private ProcessedEventRepository processed;

    @Test
    @DisplayName("one event produces exactly one notification, addressed to the shopper")
    void consumesAnEvent() {
        long orderId = 9_001L;
        UUID eventId = UUID.randomUUID();

        publish(eventId, orderId, "asha");

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(notifications.findByOrderId(orderId))
                        .as("a notification for order %s", orderId)
                        .singleElement()
                        .satisfies(notification -> {
                            assertThat(notification.getRecipient()).isEqualTo("asha");
                            assertThat(notification.getMessage()).contains(String.valueOf(orderId));
                        }));

        // The marker is what makes the next test's claim possible at all.
        assertThat(processed.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName("a REDELIVERED event writes no second notification - the consumer is idempotent")
    void ignoresARedelivery() {
        long orderId = 9_002L;
        UUID eventId = UUID.randomUUID();

        // The SAME event id twice, which is exactly what at-least-once delivery does after a consumer
        // dies between handling a record and committing its offset. Kafka has no way to know the work
        // was done; the ledger does.
        publish(eventId, orderId, "bilal");
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(notifications.findByOrderId(orderId)).hasSize(1));

        publish(eventId, orderId, "bilal");

        // Waiting for something NOT to happen needs a different shape: `untilAsserted` alone would pass
        // on its first check, before a second notification could possibly have been written. The poll
        // delay gives the consumer time to do the wrong thing and then asserts it did not.
        await().pollDelay(Duration.ofSeconds(3)).atMost(PATIENCE).untilAsserted(() ->
                assertThat(notifications.countByOrderId(orderId))
                        .as("still exactly one notification after a redelivery")
                        .isEqualTo(1));
    }

    @Test
    @DisplayName("a poison message does not block the partition behind it")
    void survivesAPoisonMessage() {
        long goodOrderId = 9_003L;

        // A payload the deserializer cannot turn into an event. Before Phase 17's retry topics this
        // would have been retried for ever with its offset never committed, and every message behind it
        // on the partition would have waited - head-of-line blocking, which looks like the consumer
        // hanging rather than like one bad record.
        kafka.send(Topics.ORDERS_PLACED, "poison", "{\"this\":\"is not an OrderPlacedEvent\"}");

        publish(UUID.randomUUID(), goodOrderId, "chandra");

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(notifications.findByOrderId(goodOrderId))
                        .as("the message AFTER the poison one still got through")
                        .hasSize(1));
    }

    /**
     * Publishes a MAP rather than the record, deliberately.
     *
     * <p>{@code OrderPlacedEvent} here is this service's OWN copy of the contract — order-service has
     * its own. Sending a map means the test writes the JSON the producer actually puts on the wire,
     * rather than serialising the consumer's view of it and deserialising it back. Round-tripping
     * through one definition would pass however that definition drifted, which is precisely the risk
     * that not sharing a jar creates.
     */
    private void publish(UUID eventId, long orderId, String username) {
        kafka.send(Topics.ORDERS_PLACED, String.valueOf(orderId), Map.of(
                "eventId", eventId.toString(),
                "orderId", orderId,
                "username", username,
                "totalAmount", new BigDecimal("59.97"),
                "itemCount", 3,
                "placedAt", Instant.now().toString()));
    }
}

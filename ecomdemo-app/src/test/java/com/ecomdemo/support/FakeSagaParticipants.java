package com.ecomdemo.support;

import com.ecomdemo.clients.inventory.InventoryGateway;
import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.messaging.OrderCreatedEvent;
import com.ecomdemo.order.internal.saga.PaymentCompletedEvent;
import com.ecomdemo.order.internal.saga.PaymentFailedEvent;
import com.ecomdemo.order.internal.saga.StockRejectedEvent;
import com.ecomdemo.shared.InsufficientStockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * inventory-service and payment-service, played by one fake, over the REAL Kafka container
 * (Phase 24).
 *
 * <p>The integration suite runs this application alone. Before the saga that was enough: checkout
 * reserved stock through {@code InventoryGateway}, which the suite replaced with
 * {@link InMemoryInventory}. Now checkout only publishes {@code OrderCreated}, and without
 * somebody to answer it every order here would stay PENDING for ever.
 *
 * <p>So this answers it - through Kafka, not through a method call. The application's outbox and
 * relay publish {@code orders.created} to the container; this listener reads it, reserves in the
 * same in-memory inventory the rest of the suite uses, and publishes the reply the real services
 * would; the application's REAL saga listeners consume that reply and confirm or cancel the order.
 * Every part of the order service's side is real. Only the other two services are stand-ins, and
 * they are tested for real in their own modules and together in the smoke test.
 *
 * <p>It collapses inventory's and payment's steps into one - there is no
 * {@code inventory.stock-reserved} hop in between - because that hop is invisible to the order
 * service, which is what these tests are about.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeSagaParticipants {

    /** The same rule as the real payment-service's default: decline totals above 10,000.00. */
    public static final BigDecimal DECLINE_ABOVE = new BigDecimal("10000.00");

    private static final AtomicLong PAYMENT_IDS = new AtomicLong(1);

    /**
     * The reply topics, which in compose are declared by the services that publish them. Nothing
     * here publishes them but this fake, so it declares them.
     */
    @Bean
    NewTopic itStockRejectedTopic() {
        return TopicBuilder.name(KafkaTopics.STOCK_REJECTED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic itPaymentsCompletedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENTS_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic itPaymentsFailedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENTS_FAILED).partitions(3).replicas(1).build();
    }

    @Bean
    Participants fakeSagaParticipants(InventoryGateway inventory, KafkaTemplate<String, Object> kafka) {
        return new Participants(inventory, kafka);
    }

    /** The listener itself; a bean so that {@code @KafkaListener} is picked up. */
    public static class Participants {

        private final InventoryGateway inventory;
        private final KafkaTemplate<String, Object> kafka;

        Participants(InventoryGateway inventory, KafkaTemplate<String, Object> kafka) {
            this.inventory = inventory;
            this.kafka = kafka;
        }

        @KafkaListener(
                topics = KafkaTopics.ORDERS_CREATED,
                groupId = "it-fake-inventory-and-payment",
                properties = "spring.json.value.default.type=com.ecomdemo.messaging.OrderCreatedEvent")
        void onOrderCreated(OrderCreatedEvent order) throws Exception {
            String key = String.valueOf(order.orderId());

            // inventory-service's step: all or nothing. Synchronised because the listener runs one
            // thread per partition, and "check every line, then take every line" must not
            // interleave with another order's - the real service holds row locks for the same
            // reason.
            String rejection = reserveAll(order);
            if (rejection != null) {
                kafka.send(KafkaTopics.STOCK_REJECTED, key, new StockRejectedEvent(
                        UUID.randomUUID(), order.orderId(), rejection, Instant.now())).get();
                return;
            }

            // payment-service's step, with its compensation (inventory's reaction to a decline).
            if (order.totalAmount().compareTo(DECLINE_ABOVE) > 0) {
                order.lines().forEach(line -> inventory.release(line.productId(), line.quantity()));
                kafka.send(KafkaTopics.PAYMENTS_FAILED, key, new PaymentFailedEvent(
                        UUID.randomUUID(), order.orderId(), order.totalAmount(),
                        "Payment declined: %s exceeds the limit of %s".formatted(
                                order.totalAmount().toPlainString(), DECLINE_ABOVE.toPlainString()),
                        Instant.now())).get();
                return;
            }
            kafka.send(KafkaTopics.PAYMENTS_COMPLETED, key, new PaymentCompletedEvent(
                    UUID.randomUUID(), order.orderId(), PAYMENT_IDS.getAndIncrement(),
                    order.totalAmount(), Instant.now())).get();
        }

        private synchronized String reserveAll(OrderCreatedEvent order) {
            try {
                order.lines().forEach(line ->
                        inventory.requireAvailable(line.productId(), line.productName(), line.quantity()));
            } catch (InsufficientStockException e) {
                return e.getMessage();
            }
            order.lines().forEach(line ->
                    inventory.reserve(line.productId(), line.productName(), line.quantity()));
            return null;
        }
    }
}

package com.ecomdemo.inventory.saga;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Where the saga's messages enter inventory-service. Deliberately thin: everything that matters
 * happens in {@link InventorySagaHandler}'s transaction.
 *
 * <p>Each listener names the type its JSON becomes. The producers send no type headers
 * ({@code spring.json.add.type.headers=false}, Phase 17: a header naming the PRODUCER's class
 * would couple every consumer to it), so the consumer says what it expects - per listener,
 * because this service reads two topics carrying two different records.
 *
 * <p>A message that fails is retried in place and then dead-lettered by the error handler from
 * {@code SagaListenerErrors} - never retried on a separate topic, which would let one order's
 * events overtake each other.
 */
@Component
public class InventorySagaListeners {

    private final InventorySagaHandler handler;

    public InventorySagaListeners(InventorySagaHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            topics = SagaTopics.ORDERS_CREATED,
            groupId = SagaTopics.GROUP,
            properties = "spring.json.value.default.type=com.ecomdemo.inventory.saga.OrderCreatedEvent")
    void onOrderCreated(OrderCreatedEvent event) {
        handler.onOrderCreated(event);
    }

    @KafkaListener(
            topics = SagaTopics.PAYMENTS_FAILED,
            groupId = SagaTopics.GROUP,
            properties = "spring.json.value.default.type=com.ecomdemo.inventory.saga.PaymentFailedEvent")
    void onPaymentFailed(PaymentFailedEvent event) {
        handler.onPaymentFailed(event);
    }
}

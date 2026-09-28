package com.ecomdemo.order.internal.saga;

import com.ecomdemo.messaging.KafkaTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Where the saga's replies enter the order service. Thin, as in the other services: each listener
 * names the record its JSON becomes (the producers send no type headers) and hands it on.
 */
@Component
class OrderSagaListeners {

    private final OrderSagaHandler handler;

    OrderSagaListeners(OrderSagaHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            topics = KafkaTopics.STOCK_REJECTED,
            groupId = KafkaTopics.ORDER_SAGA_GROUP,
            properties = "spring.json.value.default.type=com.ecomdemo.order.internal.saga.StockRejectedEvent")
    void onStockRejected(StockRejectedEvent event) {
        handler.onStockRejected(event);
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENTS_COMPLETED,
            groupId = KafkaTopics.ORDER_SAGA_GROUP,
            properties = "spring.json.value.default.type=com.ecomdemo.order.internal.saga.PaymentCompletedEvent")
    void onPaymentCompleted(PaymentCompletedEvent event) {
        handler.onPaymentCompleted(event);
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENTS_FAILED,
            groupId = KafkaTopics.ORDER_SAGA_GROUP,
            properties = "spring.json.value.default.type=com.ecomdemo.order.internal.saga.PaymentFailedEvent")
    void onPaymentFailed(PaymentFailedEvent event) {
        handler.onPaymentFailed(event);
    }
}

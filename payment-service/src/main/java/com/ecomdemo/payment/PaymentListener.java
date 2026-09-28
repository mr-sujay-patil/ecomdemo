package com.ecomdemo.payment;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Where StockReserved enters payment-service. Thin, like inventory's listeners. */
@Component
public class PaymentListener {

    private final PaymentService payments;

    public PaymentListener(PaymentService payments) {
        this.payments = payments;
    }

    @KafkaListener(
            topics = PaymentTopics.STOCK_RESERVED,
            groupId = PaymentTopics.GROUP,
            properties = "spring.json.value.default.type=com.ecomdemo.payment.StockReservedEvent")
    void onStockReserved(StockReservedEvent event) {
        payments.onStockReserved(event);
    }
}

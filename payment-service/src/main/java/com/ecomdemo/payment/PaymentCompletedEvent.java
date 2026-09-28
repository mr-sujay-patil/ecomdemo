package com.ecomdemo.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The order has been paid for. Next: the order service confirms it. The saga ends well. */
public record PaymentCompletedEvent(
        UUID eventId, Long orderId, Long paymentId, BigDecimal amount, Instant completedAt) {

    static PaymentCompletedEvent of(Payment payment) {
        return new PaymentCompletedEvent(
                UUID.randomUUID(), payment.getOrderId(), payment.getId(), payment.getAmount(), Instant.now());
    }
}

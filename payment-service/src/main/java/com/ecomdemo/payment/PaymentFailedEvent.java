package com.ecomdemo.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The charge was declined. TWO services react, independently: inventory-service gives the stock
 * back (the compensation) and the order service cancels the order. Neither waits for the other.
 */
public record PaymentFailedEvent(
        UUID eventId, Long orderId, BigDecimal amount, String reason, Instant failedAt) {

    static PaymentFailedEvent of(Payment payment) {
        return new PaymentFailedEvent(
                UUID.randomUUID(), payment.getOrderId(), payment.getAmount(), payment.getReason(), Instant.now());
    }
}

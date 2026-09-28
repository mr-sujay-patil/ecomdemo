package com.ecomdemo.order.internal.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The order service's copy of payment-service's event: payment was declined. inventory-service
 * reads the same event and gives the stock back; this service only cancels the order.
 */
public record PaymentFailedEvent(
        UUID eventId, Long orderId, BigDecimal amount, String reason, Instant failedAt) {
}

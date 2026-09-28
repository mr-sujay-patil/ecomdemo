package com.ecomdemo.order.internal.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The order service's copy of payment-service's event: the order is paid for. */
public record PaymentCompletedEvent(
        UUID eventId, Long orderId, Long paymentId, BigDecimal amount, Instant completedAt) {
}

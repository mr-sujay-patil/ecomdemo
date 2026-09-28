package com.ecomdemo.inventory.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * inventory-service's copy of payment-service's {@code PaymentFailedEvent}. The trigger for the
 * saga's compensation: the stock this service reserved must be given back.
 *
 * <p>It names only the order. What to give back is in {@code stock_reservation}, which is exactly
 * why that table exists.
 */
public record PaymentFailedEvent(
        UUID eventId, Long orderId, BigDecimal amount, String reason, Instant failedAt) {
}

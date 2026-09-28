package com.ecomdemo.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** payment-service's copy of inventory-service's event: stock is held, so charge for it. */
public record StockReservedEvent(
        UUID eventId, Long orderId, String username, BigDecimal totalAmount, Instant reservedAt) {
}

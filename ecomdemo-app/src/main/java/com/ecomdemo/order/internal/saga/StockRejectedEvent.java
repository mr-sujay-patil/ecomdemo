package com.ecomdemo.order.internal.saga;

import java.time.Instant;
import java.util.UUID;

/** The order service's copy of inventory-service's event: the order could not be reserved. */
public record StockRejectedEvent(UUID eventId, Long orderId, String reason, Instant rejectedAt) {
}

package com.ecomdemo.inventory.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * inventory-service's copy of the order service's {@code OrderCreatedEvent}: the saga's first
 * step. It carries every line, because reserving them is this service's job and it has no other
 * way to learn what was ordered.
 *
 * <p>It also carries the total and the username, which inventory does not need, so it can pass
 * them on in {@link StockReservedEvent}. That is choreography's price: with no coordinator
 * holding the order's state, each event carries what the NEXT step will need.
 */
public record OrderCreatedEvent(
        UUID eventId,
        Long orderId,
        String username,
        BigDecimal totalAmount,
        List<Line> lines,
        Instant createdAt) {

    /** One order line. */
    public record Line(Long productId, String productName, int quantity) {
    }
}

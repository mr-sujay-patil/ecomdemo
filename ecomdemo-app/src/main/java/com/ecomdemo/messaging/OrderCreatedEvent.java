package com.ecomdemo.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The saga's first event (Phase 24): a checkout created a PENDING order.
 *
 * <p>Carries every line because inventory-service reserves them and has no other way to learn
 * what was ordered, and the total and username because inventory passes them on to payment. In
 * choreography there is no coordinator holding the order's state for later steps to ask, so the
 * events carry it forward.
 *
 * <p>Like {@link OrderPlacedEvent}, a hand-picked record and never the entity: it is a published
 * contract, and each consumer keeps its own copy of it.
 *
 * @param eventId the idempotency key, generated once here - see {@link OrderPlacedEvent#of}
 */
public record OrderCreatedEvent(
        UUID eventId,
        Long orderId,
        String username,
        BigDecimal totalAmount,
        List<Line> lines,
        Instant createdAt) {

    /** One order line: what inventory must reserve. */
    public record Line(Long productId, String productName, int quantity) {
    }

    public static OrderCreatedEvent of(
            Long orderId, String username, BigDecimal totalAmount, List<Line> lines, Instant createdAt) {
        return new OrderCreatedEvent(
                UUID.randomUUID(), orderId, username, totalAmount, List.copyOf(lines), createdAt);
    }
}

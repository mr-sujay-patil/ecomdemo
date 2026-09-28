package com.ecomdemo.inventory.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Every line of an order is held. Next: payment-service charges for it.
 *
 * @param totalAmount passed through from {@link OrderCreatedEvent}, because it is what payment
 *     charges and payment-service has no other source for it
 */
public record StockReservedEvent(
        UUID eventId, Long orderId, String username, BigDecimal totalAmount, Instant reservedAt) {

    static StockReservedEvent of(OrderCreatedEvent order) {
        return new StockReservedEvent(
                UUID.randomUUID(), order.orderId(), order.username(), order.totalAmount(), Instant.now());
    }
}

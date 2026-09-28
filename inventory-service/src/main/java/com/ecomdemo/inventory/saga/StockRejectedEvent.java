package com.ecomdemo.inventory.saga;

import java.time.Instant;
import java.util.UUID;

/**
 * An order could not be reserved, so the saga ends here: the order service cancels it. Nothing
 * was taken, so there is nothing to compensate.
 *
 * @param reason in the shopper's terms, e.g. "Insufficient stock for 'Mouse': requested 3,
 *     available 2"
 */
public record StockRejectedEvent(UUID eventId, Long orderId, String reason, Instant rejectedAt) {

    static StockRejectedEvent of(Long orderId, String reason) {
        return new StockRejectedEvent(UUID.randomUUID(), orderId, reason, Instant.now());
    }
}

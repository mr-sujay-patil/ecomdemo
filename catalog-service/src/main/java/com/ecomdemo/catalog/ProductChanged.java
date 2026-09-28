package com.ecomdemo.catalog;

import java.util.UUID;

/**
 * "Product {@code productId} was created, changed or deleted" (Phase 28).
 *
 * <p>It carries the id and nothing else, deliberately. A consumer that needs the product reads its
 * CURRENT state, so events that arrive twice or out of order cannot leave it holding an old
 * version: the last one processed always sees the latest row. That is what makes the search
 * indexer idempotent without a ledger of processed events. A payload with the product's fields
 * would invite the opposite - applying a stale snapshot over a newer one.
 *
 * <p>Written through the outbox in the same transaction as the change, so an event exists if and
 * only if the change committed.
 *
 * @param eventId unique per event; the outbox's key, and what a consumer would de-duplicate on
 * @param productId the product that changed
 */
public record ProductChanged(UUID eventId, Long productId) {

    /** The topic, named after its producer and what happened, like {@code inventory.stock-changed}. */
    public static final String TOPIC = "catalog.product-changed";

    /** The outbox's aggregate type: every event about one product shares its partition, in order. */
    public static final String AGGREGATE = "Product";

    public static ProductChanged of(Long productId) {
        return new ProductChanged(UUID.randomUUID(), productId);
    }
}

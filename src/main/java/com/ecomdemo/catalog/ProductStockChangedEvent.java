package com.ecomdemo.catalog;

/**
 * Published when a product's stock is changed by something other than a catalogue edit — today,
 * only a checkout.
 *
 * <p><strong>Why an event rather than a method call.</strong> The thing that has to happen next is
 * a cache eviction, and ordering has no business knowing that a cache exists. An event lets
 * {@code ProductService} state a fact about the domain — this product's stock moved — and leaves
 * whoever cares to react. Today that is one listener in the cache package; from Phase 17 the same
 * fact is the natural thing to publish to Kafka, and nothing about this class has to change for
 * that to happen.
 *
 * <p><strong>Why it carries an id and not the product.</strong> The listener runs <em>after the
 * transaction has committed</em>, by which time the entity is detached and its persistence context
 * is gone; touching a lazy association on it would throw. An id is a value, it is still true after
 * the commit, and it is all an eviction needs.
 *
 * @param productId the product whose stock changed
 */
public record ProductStockChangedEvent(Long productId) {}

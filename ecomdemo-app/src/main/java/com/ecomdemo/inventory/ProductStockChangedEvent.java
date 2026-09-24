package com.ecomdemo.inventory;

/**
 * Published when a product's stock is changed by something other than a catalogue edit — today,
 * only a checkout.
 *
 * <p><strong>It moved here from {@code catalog} in Phase 20</strong>, with the column it describes.
 * Stock is the inventory module's to state a fact about now; the catalogue no longer has the
 * number and could not raise the event honestly if it wanted to.
 *
 * <p><strong>Why an event rather than a method call.</strong> The thing that has to happen next is
 * a cache eviction, and neither ordering nor inventory has any business knowing that a cache
 * exists. An event lets this module state a fact about the domain — this product's stock moved —
 * and leaves whoever cares to react. Today that is one listener in the cache package; from Phase 17 the same
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

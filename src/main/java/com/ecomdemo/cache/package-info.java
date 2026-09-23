/**
 * Redis configuration and the eviction that follows a stock change.
 *
 * <p>It depends on the catalogue and on its published DTOs, and on nothing else: it needs
 * {@code ProductResponse} as a type token to build a typed serializer, and it listens for
 * {@code ProductStockChangedEvent} so a sale evicts the entries it invalidated. Both targets
 * are named separately because a named interface is not covered by naming the module. The sharper design would
 * invert that, letting the catalogue contribute its own cache configuration so this module never
 * learns that products exist; that is recorded in {@code docs/decisions.md} as considered and
 * deferred, because it is a change to caching rather than to boundaries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Cache",
        allowedDependencies = {"catalog :: dto", "inventory"})
package com.ecomdemo.cache;

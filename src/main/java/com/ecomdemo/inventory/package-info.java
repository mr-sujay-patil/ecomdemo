/**
 * How many of a product there are, and the only route by which that number changes.
 *
 * <p>A catalogue and an inventory answer different questions about the same row, at very
 * different rates, with very different correctness stories - a description changes rarely and by
 * a human, a count changes on every sale, concurrently, under an optimistic lock. That divergence
 * is what a bounded context is, and separating it before Phase 20 splits the services is why this
 * phase comes first.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Inventory",
        allowedDependencies = {"catalog", "shared"})
package com.ecomdemo.inventory;

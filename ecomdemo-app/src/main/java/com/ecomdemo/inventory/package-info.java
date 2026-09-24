/**
 * What is left of inventory in this application: a client, not a module.
 *
 * <p>Until Phase 20b this package held the entity, the repository and the service that owned
 * stock. All of that is in {@code inventory-service} now. What remains is an HTTP client and the
 * address to find it at — the shadow a module leaves behind when it becomes a deployable.
 *
 * <p>The package keeps its name on purpose. Every caller still imports
 * {@code com.ecomdemo.inventory.InventoryClient} and calls methods with the arguments it always
 * used, so the diff at the five call sites is a type name. That is the payoff for Phase 20a
 * shaping those calls — batched, id-based, carrying the product name for the error message —
 * while they were still in-process.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Inventory (client)",
        allowedDependencies = {"shared"})
package com.ecomdemo.inventory;

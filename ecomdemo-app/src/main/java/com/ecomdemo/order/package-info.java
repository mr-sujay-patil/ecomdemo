/**
 * Checkout: the one place in the application where several aggregates change together.
 *
 * <p>The widest dependency list here, and that is honest rather than a smell - a checkout is by
 * definition the thing that touches the cart, the catalogue, the inventory and the customer at
 * once. What matters is that every one of those edges is to a published API, and that the list is
 * DECLARED: adding an eighth dependency is now an edit to this file rather than an import nobody
 * notices.
 *
 * <p>Three of those edges have gone since Phase 19 began. {@code security} went with
 * {@code CurrentUser}; stock reduction went to {@code inventory}; and in Phase 20
 * {@code catalog} went too, when the cart started remembering the product's name and price
 * instead of holding an association to it.
 *
 * <p>That last one matters for what comes next: <strong>checkout no longer reads a product at
 * all.</strong> Every value an order line needs is already in the cart, so order-service will be
 * able to place an order without calling catalog-service - the price charged is the price the
 * shopper was shown, and no network hop stands between the two.
 *
 * <p><strong>Phase 24</strong> added {@code outbox}: the saga's replies are received here, and
 * each is claimed through {@code ProcessedEvents} so a redelivery cannot confirm or cancel twice.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Order",
        allowedDependencies = {"cart", "clients :: inventory", "jwt", "messaging", "metrics", "outbox", "shared"})
package com.ecomdemo.order;

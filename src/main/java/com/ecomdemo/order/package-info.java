/**
 * Checkout: the one place in the application where several aggregates change together.
 *
 * <p>The widest dependency list here, and that is honest rather than a smell - a checkout is by
 * definition the thing that touches the cart, the catalogue, the inventory and the customer at
 * once. What matters is that every one of those edges is to a published API, and that the list is
 * DECLARED: adding an eighth dependency is now an edit to this file rather than an import nobody
 * notices.
 *
 * <p>Two of those edges were removed this phase. It no longer depends on {@code security} - that
 * went with {@code CurrentUser} - and it no longer reduces stock itself, which is why
 * {@code inventory} appears in its place.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Order",
        allowedDependencies = {"cart", "catalog", "customer", "inventory", "messaging", "metrics", "shared"})
package com.ecomdemo.order;

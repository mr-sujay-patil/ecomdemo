/**
 * HTTP clients for the other services, and the identity this one presents when it calls them.
 *
 * <p><strong>One module, two named interfaces.</strong> Spring Modulith treats a sub-package as
 * INTERNAL to its module unless the package says otherwise, so {@code clients.inventory} and
 * {@code clients.catalog} carry {@code @NamedInterface} — that is what makes
 * {@code InventoryGateway} and {@code CatalogGateway} usable from {@code order} and {@code cart}
 * while everything else in here stays private.
 *
 * <p>It depends on {@code jwt} for the service token it signs and {@code shared} for the exception
 * types it maps remote errors back into, and on nothing else. A client that needed a domain module
 * would not be a client.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Service clients",
        allowedDependencies = {"jwt", "shared"})
package com.ecomdemo.clients;

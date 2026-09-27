/**
 * How this service behaves when catalog-service is slow, failing, or gone (Phase 22).
 *
 * <p>Nothing in {@code cart} or {@code batch} knows this module exists. They depend on
 * {@code CatalogGateway}; this module swaps the HTTP implementation of that interface for a
 * decorated one when the context starts. That is the point of having had an interface: the
 * policy for calling a remote service is a concern of the WIRING, and the callers keep the logic
 * they had when the catalogue was a local repository.
 *
 * <p>Nothing depends on this module, and it depends on the catalog client and on {@code shared}
 * (for the 503 it throws) only.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Resilience",
        allowedDependencies = {"clients :: catalog", "shared"})
package com.ecomdemo.resilience;

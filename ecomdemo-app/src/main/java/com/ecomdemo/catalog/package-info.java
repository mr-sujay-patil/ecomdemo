/**
 * The public face of the catalogue: a thin proxy to catalog-service.
 *
 * <p>The catalogue itself left this application in Phase 20c. What remains is the public HTTP
 * surface, kept here so that the split stays invisible to shoppers and to the smoke test — and so
 * that catalog-service can keep requiring a service token on every path. Phase 21 replaces it with
 * an API gateway.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Catalog (public proxy)",
        allowedDependencies = {"clients :: catalog", "shared"})
package com.ecomdemo.catalog;

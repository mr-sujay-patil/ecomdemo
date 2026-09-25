/**
 * The public face of accounts and login: a thin proxy to customer-service.
 *
 * <p>Accounts left this application in Phase 20d. What remains is the HTTP surface, kept here so the
 * split stays invisible to shoppers and to the smoke test. Phase 21 replaces it with an API gateway —
 * and in this case the gateway fixes something real, not just tidiness: a proxied login puts a
 * plaintext password through this process, which a gateway would not.
 *
 * <p>It is called {@code identity} rather than {@code customer} or {@code auth} deliberately. Those
 * two names now belong to customer-service, and reusing either here would invite the reading that
 * this application still owns accounts or still issues tokens. It owns neither; it forwards.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity (public proxy)",
        allowedDependencies = {"clients :: customer", "shared"})
package com.ecomdemo.identity;

package com.ecomdemo.jwt;

/**
 * The identity one service uses when it calls another on nobody's behalf, and what it may do.
 *
 * <p><strong>Why a service identity at all, rather than passing the caller's token along?</strong>
 * Token relay is the better pattern where it works — the callee sees who is really being served,
 * and an expired or revoked token stops the whole chain. It does not work here, and the two places
 * it breaks are worth naming:
 *
 * <ul>
 *   <li>The product listing is <em>anonymous</em> and shows stock. There is no caller token to
 *       relay, because there is no caller identity.
 *   <li>The CSV import (Phase 14) sets stock from a <em>background job thread</em>, long after the
 *       request that uploaded the file has returned. There is no {@code SecurityContext} there
 *       either.
 * </ul>
 *
 * <p><strong>Scoped since Phase 33, instead of one shared role.</strong> Until then every service
 * token carried {@code ROLE_SERVICE}, so catalog-service could write stock and the gateway could ask
 * payment-service to settle an order: anything one service may do, all could. Now each service gets
 * its token from customer-service ({@code POST /oauth2/token}, with its own client secret), and the
 * token lists only what that service needs. Each callee checks the scope its endpoint requires:
 *
 * <table>
 *   <caption>Who may call what</caption>
 *   <tr><th>Service</th><th>Scopes</th></tr>
 *   <tr><td>gateway-service</td><td>{@code catalog:read} (anonymous browsing)</td></tr>
 *   <tr><td>ecomdemo-app</td><td>{@code catalog:read catalog:write inventory:read
 *       inventory:write payment:settle}</td></tr>
 *   <tr><td>catalog-service</td><td>{@code inventory:read}</td></tr>
 * </table>
 *
 * <p>The list is configured in customer-service, which issues the tokens; these constants are the
 * names both sides agree on.
 */
public final class ServiceTokens {

    public static final String CATALOG_READ = "catalog:read";

    public static final String CATALOG_WRITE = "catalog:write";

    public static final String INVENTORY_READ = "inventory:read";

    public static final String INVENTORY_WRITE = "inventory:write";

    public static final String PAYMENT_SETTLE = "payment:settle";

    private ServiceTokens() {
    }

    /** The authority a scope becomes after {@link JwtAuthorities}: {@code SCOPE_catalog:read}. */
    public static String authority(String scope) {
        return JwtAuthorities.SCOPE_PREFIX + scope;
    }
}

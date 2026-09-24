package com.ecomdemo.jwt;

/**
 * The identity one service uses when it calls another on nobody's behalf.
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
 * <p>So the caller authenticates as itself. Note what this gives up: inventory-service can no
 * longer tell an administrator from a shopper, so <em>authorisation</em> stays at the edge, in the
 * service that owns the endpoint the human called. That is the usual shape — the edge authorises,
 * the internal service authenticates — and it is only safe while the internal service is not
 * reachable from outside. Recorded in {@code docs/decisions.md}.
 */
public final class ServiceTokens {

    /** The {@code roles} entry, so a service call arrives as the authority {@code ROLE_SERVICE}. */
    public static final String ROLE = "SERVICE";

    private ServiceTokens() {
    }
}

package com.ecomdemo.jwt;

/**
 * The token a service presents when it calls another service. See {@link ServiceTokens} for why a
 * service identity exists and what each one may do.
 *
 * <p>An interface since Phase 33: the real one ({@link ClientCredentialsTokenProvider}) asks
 * customer-service for a token over HTTP, and a test supplies a fixed one without a network.
 */
@FunctionalInterface
public interface ServiceTokenProvider {

    /** A current token, as the value of {@code Authorization: Bearer <token>}. */
    String token();
}

package com.ecomdemo.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What every service needs to know about tokens, under {@code ecomdemo.jwt}.
 *
 * <p>Phase 33 removed {@code secret}. There is no shared key any more: customer-service signs with a
 * private key only it holds, and everyone else fetches the matching PUBLIC keys from
 * {@code jwkSetUri}. A public key can verify a signature and cannot make one, which is the whole
 * point of the change.
 *
 * @param issuer the {@code iss} every token must carry, and that customer-service writes
 * @param expiry how long a token lives; customer-service issues with it, callers cache by it
 * @param jwkSetUri where customer-service publishes its public keys (its {@code /oauth2/jwks});
 *     unset in customer-service itself, which verifies against the keys it already holds
 */
@ConfigurationProperties(prefix = "ecomdemo.jwt")
public record JwtProperties(String issuer, Duration expiry, String jwkSetUri) {
}

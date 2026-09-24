package com.ecomdemo.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How tokens are signed and how long they last.
 *
 * <p>Bound from {@code ecomdemo.jwt.*}. A record works as a {@code @ConfigurationProperties}
 * type because Spring Boot binds through the canonical constructor — the values are immutable
 * and validated once, at startup, rather than read out of the {@code Environment} at the moment
 * a token is signed.
 *
 * @param secret the HMAC signing key, as text. Supplied by {@code JWT_SECRET}; see
 *     {@link JwtConfig} for what happens when it is not set and why that is a development-only
 *     convenience.
 * @param issuer written into the {@code iss} claim and required back on the way in, so a token
 *     minted by some other system cannot be presented here even if it happens to be signed with
 *     the same key.
 * @param expiry how long a token stays valid. Short on purpose — see {@link JwtConfig}.
 */
@ConfigurationProperties(prefix = "ecomdemo.jwt")
public record JwtProperties(String secret, String issuer, Duration expiry) {
}

package com.ecomdemo.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The three settings behind every token in the system.
 *
 * @param secret the HS256 signing key, supplied as {@code JWT_SECRET} and never committed. See
 *     {@link JwtKeyConfig} for what happens when it is not set and why that is a development-only
 *     convenience.
 * @param issuer the {@code iss} claim written into every token and required of every token
 *     presented. Every service shares one value, because they all accept each other's tokens.
 * @param expiry how long a token stays valid. Short on purpose — see {@link JwtKeyConfig}.
 */
@ConfigurationProperties(prefix = "ecomdemo.jwt")
public record JwtProperties(String secret, String issuer, Duration expiry) {
}

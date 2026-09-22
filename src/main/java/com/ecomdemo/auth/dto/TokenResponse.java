package com.ecomdemo.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * What a successful login hands back.
 *
 * <p>The field names follow RFC 6749, so a generic OAuth2 client understands them without being
 * told: {@code access_token}, {@code token_type}, {@code expires_in}. Jackson is configured
 * project-wide for camelCase, so the JSON here reads {@code accessToken} — the shape is
 * borrowed, not the wire format, and this is not an OAuth2 token endpoint.
 *
 * <p>{@code expiresIn} is a duration in seconds rather than a timestamp on purpose: a client
 * that trusts its own clock less than the server's can simply count down, and it does not have
 * to parse anything. {@code expiresAt} is included for a human reading the response.
 *
 * <p>There is no refresh token. See the README for why that is a deliberate gap.
 */
@Schema(name = "TokenResponse", description = "A signed access token and when it stops working.")
public record TokenResponse(
        @Schema(description = "The signed JWT. Send it as `Authorization: Bearer <token>`.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhc2hhIn0.Ax7...")
        String accessToken,

        @Schema(description = "Always \"Bearer\": whoever holds the token may use it, so treat it like a password.",
                example = "Bearer")
        String tokenType,

        @Schema(description = "Seconds until the token expires.", example = "900")
        long expiresIn,

        @Schema(description = "When the token expires, UTC.", example = "2026-09-22T09:30:00Z")
        Instant expiresAt) {

    public static TokenResponse bearer(String token, Instant issuedAt, Instant expiresAt) {
        return new TokenResponse(
                token, "Bearer", java.time.Duration.between(issuedAt, expiresAt).toSeconds(), expiresAt);
    }
}

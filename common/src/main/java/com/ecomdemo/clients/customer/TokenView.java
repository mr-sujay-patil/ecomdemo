package com.ecomdemo.clients.customer;

import java.time.Instant;

/**
 * The token customer-service hands back.
 *
 * <p><strong>The field names are copied from the wire, not invented.</strong> The first version of this
 * record guessed at {@code expiresInSeconds} and omitted {@code expiresAt}, and the result was a 400
 * "Malformed request body" on every single login — with the request body perfectly valid. The exception
 * came from reading the RESPONSE, and it happens to be the same type Spring throws for an unreadable
 * REQUEST, so the application's own error handler turned a downstream mismatch into a message that
 * blamed the caller.
 *
 * <p>That is the sharpest lesson of proxying between services: <strong>a contract you write from memory
 * is a contract you get wrong</strong>, and the error it produces can point in entirely the wrong
 * direction. The shape here was read back from a live response.
 *
 * @param expiresIn seconds until expiry, which is what an OAuth2 client expects to see.
 * @param expiresAt the same fact as an instant, because a client that has to compute it from a duration
 *     and its own clock gets it wrong when the two clocks differ.
 */
public record TokenView(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {
}

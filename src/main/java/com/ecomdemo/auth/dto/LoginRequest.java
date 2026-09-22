package com.ecomdemo.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Credentials, sent once.
 *
 * <p>This is the only request in the application that carries a password, and that is the change
 * this phase makes: with HTTP Basic the password travelled on <em>every</em> call, so every call
 * paid a BCrypt verification and every log or proxy along the way saw it again. Now it is
 * exchanged once for a token.
 */
@Schema(name = "LoginRequest", description = "Credentials exchanged for a token.")
public record LoginRequest(
        @Schema(description = "The account's login name.", example = "asha")
        @NotBlank(message = "must not be blank")
        String username,

        @Schema(description = "The account's password. Sent once, never stored, never logged.",
                example = "correct-horse-battery-staple", format = "password")
        @NotBlank(message = "must not be blank")
        String password) {

    /**
     * Never print the password, not even by accident.
     *
     * <p>A record's generated {@code toString()} includes every component, and a DTO is exactly
     * the kind of object that ends up in a debug log or an exception message. Overriding it is
     * cheap insurance.
     */
    @Override
    public String toString() {
        return "LoginRequest[username=%s, password=***]".formatted(username);
    }
}

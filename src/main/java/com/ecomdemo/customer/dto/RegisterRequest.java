package com.ecomdemo.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a client may send when registering.
 *
 * <p>Note what is <em>not</em> here: a role. Registration always creates a {@code CUSTOMER}. If
 * this record carried a role, any anonymous caller could ask to be an ADMIN, and the API would
 * hand out administrator accounts to whoever asked for one — the classic privilege-escalation
 * hole. The first ADMIN therefore arrives through migration V5 instead.
 *
 * <p>The password is a plain {@code String} on the way in, and that is unavoidable: the server
 * has to see the password once in order to hash it. What matters is that it is hashed
 * immediately, is never stored, never logged, and never appears in a response — which is why
 * {@link CustomerResponse} has no password field at all.
 */
@Schema(name = "RegisterRequest", description = "The fields needed to create a customer account.")
public record RegisterRequest(
        @Schema(description = "The login name, unique across the application.", example = "asha")
        @NotBlank(message = "must not be blank")
        @Size(min = 3, max = 50, message = "must be between 3 and 50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                message = "may contain only letters, digits, dots, underscores and hyphens")
        String username,

        @Schema(
                description = "The password. Sent once, hashed with BCrypt on arrival, and never "
                        + "returned by any endpoint.",
                example = "correct-horse-battery-staple",
                format = "password")
        @NotBlank(message = "must not be blank")
        // A minimum length is the one password rule worth enforcing. Forced symbols and digits
        // push people towards "Password1!", which is shorter and more guessable than four
        // ordinary words; length is what actually costs an attacker.
        @Size(min = 8, max = 72,
                message = "must be between 8 and 72 characters")
        String password,

        @Schema(description = "The name shown on the profile.", example = "Asha Rao")
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String fullName) {
}

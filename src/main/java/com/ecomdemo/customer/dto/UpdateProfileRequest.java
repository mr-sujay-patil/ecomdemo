package com.ecomdemo.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The only thing the owner of an account may change about it.
 *
 * <p>Username, role and password are all absent on purpose: the username identifies the account
 * everywhere else in the schema, the role is a privilege and must never be self-service, and a
 * password change needs rules of its own (re-authentication, re-encoding) rather than riding
 * along with a profile edit.
 */
@Schema(name = "UpdateProfileRequest", description = "The profile fields an account owner may change.")
public record UpdateProfileRequest(
        @Schema(description = "The name shown on the profile.", example = "Asha M. Rao")
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String fullName) {
}

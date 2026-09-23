package com.ecomdemo.customer.dto;

import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * An account as the API shows it.
 *
 * <p>There is no password field, not even the hash. A DTO is not only a convenience for mapping;
 * it is the list of things this application is willing to say out loud. Returning the entity
 * directly would publish the hash the moment somebody added a field to {@code User}, and a
 * leaked hash is an offline brute-force target. The absence is the feature.
 */
@Schema(name = "CustomerResponse", description = "An account. The password hash is never included.")
public record CustomerResponse(
        @Schema(description = "Generated identifier.", example = "2")
        Long id,

        @Schema(description = "The login name.", example = "asha")
        String username,

        @Schema(description = "The name shown on the profile.", example = "Asha Rao")
        String fullName,

        @Schema(description = "CUSTOMER for every account created through the API; ADMIN is seeded by a migration.", example = "CUSTOMER")
        Role role,

        @Schema(description = "When the account was created, UTC.", example = "2026-09-22T09:15:00Z")
        Instant createdAt) {

    public static CustomerResponse from(User user) {
        return new CustomerResponse(
                user.getId(), user.getUsername(), user.getFullName(), user.getRole(), user.getCreatedAt());
    }
}

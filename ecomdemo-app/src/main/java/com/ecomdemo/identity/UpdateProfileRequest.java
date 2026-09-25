package com.ecomdemo.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A profile change. The username and role are deliberately not editable here. */
public record UpdateProfileRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String fullName) {
}

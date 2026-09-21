package com.ecomdemo.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * What a client may send when creating or updating a product.
 *
 * <p>The constraints run before any of our code does, so the service can assume the data is
 * structurally sane and only has to enforce business rules.
 */
public record ProductRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Size(max = 1000, message = "must be at most 1000 characters")
        String description,

        @NotNull(message = "is required")
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal price,

        @NotNull(message = "is required")
        @PositiveOrZero(message = "must not be negative")
        Integer stockQuantity) {
}

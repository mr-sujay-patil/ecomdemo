package com.ecomdemo.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 *
 * <p>springdoc reads those same constraints: {@code @NotBlank} and {@code @NotNull} become
 * {@code required}, {@code @Size} becomes {@code maxLength}, {@code @DecimalMin} becomes
 * {@code minimum}. Only the examples and prose below have to be written by hand.
 */
@Schema(name = "ProductRequest", description = "The catalogue fields a client may set.")
public record ProductRequest(
        @Schema(description = "Display name, unique only by convention.", example = "Mechanical Keyboard")
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Schema(description = "Optional long description.", example = "Hot-swappable switches, PBT keycaps, 87 keys.")
        @Size(max = 1000, message = "must be at most 1000 characters")
        String description,

        @Schema(description = "Unit price, at most two decimal places.", example = "8999.00")
        @NotNull(message = "is required")
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places")
        BigDecimal price,

        @Schema(description = "Units available to sell. Zero is allowed; negative is not.", example = "25")
        @NotNull(message = "is required")
        @PositiveOrZero(message = "must not be negative")
        Integer stockQuantity,

        @Schema(description = "Optional catalogue category. Omitting it is allowed: the column "
                + "is nullable so that the migration that added it could not break clients "
                + "written before it existed.", example = "PERIPHERALS")
        @Size(max = 50, message = "must be at most 50 characters")
        String category) {
}

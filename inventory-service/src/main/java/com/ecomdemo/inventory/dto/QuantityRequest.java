package com.ecomdemo.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A quantity, for the three endpoints that take one.
 *
 * <p>Two constraints rather than one shared annotation, because the endpoints disagree about zero:
 * SETTING a level to zero is meaningful ("we have none"), while reserving or releasing zero units
 * is a caller mistake. The validation lives on the fields so the 400 names which rule was broken.
 *
 * @param quantity an absolute level, for setting
 * @param units a movement, for reserving and releasing
 * @param productName carried on a reserve so the 409 can name the product — inventory-service has
 *     no catalogue to look one up in, which is the small, honest tax of the split
 */
public record QuantityRequest(
        @PositiveOrZero(message = "quantity must not be negative") Integer quantity,
        @Positive(message = "units must be greater than zero") Integer units,
        String productName) {

    /** For {@code PUT}: an absolute level. */
    public static QuantityRequest level(@NotNull Integer quantity) {
        return new QuantityRequest(quantity, null, null);
    }
}

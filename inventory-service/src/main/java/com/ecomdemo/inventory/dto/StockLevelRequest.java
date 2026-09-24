package com.ecomdemo.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * An absolute stock level: the body of the {@code PUT}, used by the catalogue on create and update
 * and by the CSV import.
 *
 * @param quantity the level to set. Required. {@code PositiveOrZero} rather than {@code Positive},
 *     because setting a product to zero is an ordinary thing to do — it is how something goes out
 *     of stock — while a negative level is not a quantity at all.
 */
public record StockLevelRequest(
        @NotNull(message = "quantity is required")
        @PositiveOrZero(message = "quantity must not be negative")
        Integer quantity) {
}

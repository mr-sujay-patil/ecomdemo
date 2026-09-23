package com.ecomdemo.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of POST /api/cart/items. */
@Schema(name = "AddCartItemRequest", description = "A product and how many of it to put in the cart.")
public record AddCartItemRequest(
        @Schema(description = "Id of an existing product; unknown ids give 404.", example = "1")
        @NotNull(message = "is required") Long productId,

        @Schema(description = "Units to add. Adding a product already in the cart adds to its quantity.", example = "2")
        @NotNull(message = "is required") @Min(value = 1, message = "must be at least 1") Integer quantity) {
}

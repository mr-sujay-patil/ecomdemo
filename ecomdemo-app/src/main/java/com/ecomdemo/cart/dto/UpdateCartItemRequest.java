package com.ecomdemo.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/cart/items/{productId}. */
@Schema(name = "UpdateCartItemRequest", description = "The new absolute quantity for a line already in the cart.")
public record UpdateCartItemRequest(
        @Schema(description = "Replaces the current quantity; it is not added to it. Use DELETE to remove a line.", example = "3")
        @NotNull(message = "is required") @Min(value = 1, message = "must be at least 1") Integer quantity) {
}

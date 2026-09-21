package com.ecomdemo.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/cart/items/{productId}. */
public record UpdateCartItemRequest(
        @NotNull(message = "is required") @Min(value = 1, message = "must be at least 1") Integer quantity) {
}

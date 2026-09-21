package com.ecomdemo.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of POST /api/cart/items. */
public record AddCartItemRequest(
        @NotNull(message = "is required") Long productId,
        @NotNull(message = "is required") @Min(value = 1, message = "must be at least 1") Integer quantity) {
}

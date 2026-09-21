package com.ecomdemo.cart.dto;

import com.ecomdemo.cart.CartItem;
import java.math.BigDecimal;

/** One line of the cart as the API shows it, with the line total worked out server-side. */
public record CartItemResponse(
        Long productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {

    public static CartItemResponse from(CartItem item) {
        return new CartItemResponse(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getPrice(),
                item.getQuantity(),
                item.lineTotal());
    }
}

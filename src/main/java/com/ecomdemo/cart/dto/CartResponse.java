package com.ecomdemo.cart.dto;

import com.ecomdemo.cart.Cart;
import java.math.BigDecimal;
import java.util.List;

/** The whole cart: its lines plus the server-calculated total. */
public record CartResponse(Long id, List<CartItemResponse> items, BigDecimal totalAmount) {

    public static CartResponse from(Cart cart) {
        return new CartResponse(
                cart.getId(),
                cart.getItems().stream().map(CartItemResponse::from).toList(),
                cart.total());
    }
}

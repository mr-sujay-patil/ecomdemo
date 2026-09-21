package com.ecomdemo.cart.dto;

import com.ecomdemo.cart.Cart;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** The whole cart: its lines plus the server-calculated total. */
@Schema(name = "CartResponse", description = "The single shared cart. Every cart endpoint returns the whole cart after its change.")
public record CartResponse(
        @Schema(description = "Id of the one shared cart, created on first use.", example = "1")
        Long id,

        @Schema(description = "One entry per product. Empty for a new or just-checked-out cart.")
        List<CartItemResponse> items,

        @Schema(description = "The sum of every lineTotal, calculated on the server.", example = "17998.00")
        BigDecimal totalAmount) {

    public static CartResponse from(Cart cart) {
        return new CartResponse(
                cart.getId(),
                cart.getItems().stream().map(CartItemResponse::from).toList(),
                cart.total());
    }
}

package com.ecomdemo.cart.dto;

import com.ecomdemo.cart.CartItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** One line of the cart as the API shows it, with the line total worked out server-side. */
@Schema(name = "CartItemResponse", description = "One line of the cart.")
public record CartItemResponse(
        @Schema(example = "1")
        Long productId,

        @Schema(description = "Read live from the catalogue, so a rename shows up here immediately.", example = "Mechanical Keyboard")
        String productName,

        @Schema(description = "The catalogue price right now, not a snapshot. It is fixed only at checkout.", example = "8999.00")
        BigDecimal unitPrice,

        @Schema(example = "2")
        int quantity,

        @Schema(description = "unitPrice x quantity, calculated on the server.", example = "17998.00")
        BigDecimal lineTotal) {

    public static CartItemResponse from(CartItem item) {
        return new CartItemResponse(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getPrice(),
                item.getQuantity(),
                item.lineTotal());
    }
}

package com.ecomdemo.order.dto;

import com.ecomdemo.order.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** One line of an order, priced as it was at checkout. */
@Schema(name = "OrderItemResponse", description = "One line of a placed order, frozen at checkout time.")
public record OrderItemResponse(
        @Schema(description = "The product ordered. It is only a reference: the product may since have been deleted.", example = "1")
        Long productId,

        @Schema(description = "The name as it was at checkout, copied into the order on purpose.", example = "Mechanical Keyboard")
        String productName,

        @Schema(description = "The price paid. It does not follow later catalogue changes.", example = "8999.00")
        BigDecimal unitPrice,

        @Schema(example = "2")
        int quantity,

        @Schema(description = "unitPrice x quantity.", example = "17998.00")
        BigDecimal lineTotal) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.lineTotal());
    }
}

package com.ecomdemo.order.dto;

import com.ecomdemo.order.OrderItem;
import java.math.BigDecimal;

/** One line of an order, priced as it was at checkout. */
public record OrderItemResponse(
        Long productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.lineTotal());
    }
}

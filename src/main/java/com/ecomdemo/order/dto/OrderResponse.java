package com.ecomdemo.order.dto;

import com.ecomdemo.order.Order;
import com.ecomdemo.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A placed order as the API shows it. */
public record OrderResponse(
        Long id,
        Instant placedAt,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getPlacedAt(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}

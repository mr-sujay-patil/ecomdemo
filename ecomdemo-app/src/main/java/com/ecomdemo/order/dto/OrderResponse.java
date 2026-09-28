package com.ecomdemo.order.dto;

import com.ecomdemo.order.Order;
import com.ecomdemo.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A placed order as the API shows it. */
@Schema(name = "OrderResponse", description = "An order. Its lines and total never change; its status moves once, from PENDING to CONFIRMED or CANCELLED, as the saga completes.")
public record OrderResponse(
        @Schema(description = "Generated identifier.", example = "1")
        Long id,

        @Schema(description = "When checkout succeeded, UTC.", example = "2026-09-21T18:30:00Z")
        Instant placedAt,

        @Schema(description = "The username of the account that placed it. Present so that a client can tell whose order it is looking at, and because the ownership rule on GET /api/orders/{id} is expressed in terms of it.", example = "asha")
        String username,

        @Schema(description = "PENDING when checkout returns; CONFIRMED once stock is reserved and payment taken; CANCELLED if either failed. Poll GET /api/orders/{id}/status for the change.", example = "PENDING")
        OrderStatus status,

        @Schema(description = "Why the order was cancelled; null unless CANCELLED.", example = "Payment declined: 12000.00 exceeds the limit of 10000.00")
        String statusReason,

        @Schema(description = "When the order left PENDING, UTC; null while PENDING.", example = "2026-09-21T18:30:03Z")
        Instant statusChangedAt,

        @Schema(description = "The cart total at checkout, stored with the order.", example = "17998.00")
        BigDecimal totalAmount,

        @Schema(description = "The cart lines, copied into the order.")
        List<OrderItemResponse> items) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getPlacedAt(),
                order.getUsername(),
                order.getStatus(),
                order.getStatusReason(),
                order.getStatusChangedAt(),
                order.getTotalAmount(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}

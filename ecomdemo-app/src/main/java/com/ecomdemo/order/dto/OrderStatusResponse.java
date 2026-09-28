package com.ecomdemo.order.dto;

import com.ecomdemo.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Where an order stands in the saga, and nothing else (Phase 24).
 *
 * <p>Checkout answers 201 with a PENDING order, and the outcome arrives a few seconds later. A
 * client polls for it; this is the small thing to poll, without the order's lines.
 */
@Schema(name = "OrderStatusResponse", description = "Where an order stands in the saga.")
public record OrderStatusResponse(
        @Schema(description = "The order's id.", example = "42")
        Long orderId,

        @Schema(description = "PENDING, CONFIRMED or CANCELLED.", example = "CONFIRMED")
        OrderStatus status,

        @Schema(description = "Why it was cancelled; null unless CANCELLED.", example = "Insufficient stock for 'Mouse': requested 3, available 2")
        String reason,

        @Schema(description = "When it left PENDING, UTC; null while PENDING.", example = "2026-09-21T18:30:03Z")
        Instant changedAt) {

    public static OrderStatusResponse from(OrderResponse order) {
        return new OrderStatusResponse(
                order.id(), order.status(), order.statusReason(), order.statusChangedAt());
    }
}

package com.ecomdemo.assistant.store;

import java.time.Instant;

/** An order's status as the application returns it (Phase 24's saga states). */
public record OrderStatusView(Long orderId, String status, String reason, Instant changedAt) {
}

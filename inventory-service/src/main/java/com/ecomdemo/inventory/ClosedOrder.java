package com.ecomdemo.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An order the saga deadline has closed (Phase 32): its stock was given back, and no reservation
 * may be made for it again. See {@code V4__closed_orders.sql} for why the release needs a fence.
 */
@Entity
@Table(name = "closed_order")
public class ClosedOrder {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    protected ClosedOrder() {
        // for JPA
    }

    ClosedOrder(Long orderId, String reason) {
        this.orderId = orderId;
        this.reason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.closedAt = Instant.now();
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}

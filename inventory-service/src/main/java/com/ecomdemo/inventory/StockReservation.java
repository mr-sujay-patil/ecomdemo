package com.ecomdemo.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Units of one product held for one order, while the saga decides whether that order happens.
 *
 * <p>Phase 24. Before the saga, a reservation was just a smaller number in {@code product_stock}
 * and the ORDER SERVICE remembered what it had taken, so that it could give it back. In a
 * choreographed saga nobody coordinates, so the service that took the stock has to remember what
 * it took - otherwise a {@code PaymentFailed} event, which carries only an order id, would arrive
 * at a service with no idea how many of what to put back.
 *
 * <p>This row is also a <strong>semantic lock</strong>: while it says {@code RESERVED}, those units
 * belong to an order whose outcome is not yet known. They are not available to anyone else, and
 * they are not sold either - the order may still be cancelled.
 */
@Entity
@Table(name = "stock_reservation")
public class StockReservation {

    /** A reservation is either holding units or has given them back. */
    public enum Status {
        RESERVED,
        RELEASED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected StockReservation() {
        // for JPA
    }

    StockReservation(Long orderId, Long productId, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = Status.RESERVED;
        this.createdAt = Instant.now();
    }

    /** The compensation's bookkeeping: the units are back, so this can never release them again. */
    void markReleased() {
        this.status = Status.RELEASED;
        this.releasedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}

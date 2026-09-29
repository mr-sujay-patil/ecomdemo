package com.ecomdemo.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One attempt to charge for one order, and how it ended.
 *
 * <p>Written for a decline as well as for a success. A declined payment is a fact the business
 * wants to keep - "why was my order cancelled?" is answered by this row - and it is what makes a
 * second attempt for the same order impossible: {@code order_id} is UNIQUE.
 */
@Entity
@Table(name = "payment")
public class Payment {

    /**
     * A mock payment has two outcomes of its own; a real one would add pending, refunded,
     * disputed... VOIDED (Phase 32) is not an outcome of charging: it records that the saga's
     * deadline closed the order before any charge was attempted, so none ever will be.
     */
    public enum Status {
        COMPLETED,
        FAILED,
        VOIDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, updatable = false)
    private Long orderId;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Status status;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Payment() {
        // for JPA
    }

    private Payment(Long orderId, BigDecimal amount, Status status, String reason) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    static Payment completed(Long orderId, BigDecimal amount) {
        return new Payment(orderId, amount, Status.COMPLETED, null);
    }

    static Payment failed(Long orderId, BigDecimal amount, String reason) {
        return new Payment(orderId, amount, Status.FAILED, reason);
    }

    static Payment voided(Long orderId, BigDecimal amount, String reason) {
        return new Payment(orderId, amount, Status.VOIDED, reason);
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.ecomdemo.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A notification that would have been sent.
 *
 * <p>Nothing here sends an email: the phase is about the messaging, not the mail. The row is the
 * side effect, which has the convenient property that "exactly one notification per order" is a
 * countable fact rather than a claim about an inbox nobody can see. The unique constraint on
 * {@code order_id} is in migration V9, so a duplicate is refused by the database even if the
 * consumer's own idempotency check were wrong.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 100)
    private String recipient;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
        // for JPA
    }

    public Notification(Long orderId, String recipient, String message) {
        this.orderId = orderId;
        this.recipient = recipient;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

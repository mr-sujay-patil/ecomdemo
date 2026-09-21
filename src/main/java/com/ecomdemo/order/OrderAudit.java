package com.ecomdemo.order;

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
 * One line in the checkout log: what was attempted and how it ended.
 *
 * <p>Deliberately not related to {@link Order}. There is no {@code @ManyToOne} and no foreign
 * key, only a plain {@code orderId} that is null for a rejection. The row is written in its own
 * transaction (see {@link OrderAuditService}), so when it is inserted the order it names may not
 * be committed yet — and when the attempt failed, no order exists at all. An audit trail has to
 * be able to describe things the rest of the schema has forgotten.
 */
@Entity
@Table(name = "order_audit")
public class OrderAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderOutcome outcome;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected OrderAudit() {
        // required by JPA
    }

    public OrderAudit(OrderOutcome outcome, Long orderId, String detail, Instant recordedAt) {
        this.outcome = outcome;
        this.orderId = orderId;
        this.detail = truncate(detail);
        this.recordedAt = recordedAt;
    }

    /**
     * Keeps {@code detail} inside the column. An exception message is not under our control —
     * it can carry a product name of any length — and an audit write that fails because the text
     * was too long would lose exactly the record it was asked to keep.
     */
    private static String truncate(String detail) {
        return detail.length() <= 500 ? detail : detail.substring(0, 497) + "...";
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public OrderOutcome getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

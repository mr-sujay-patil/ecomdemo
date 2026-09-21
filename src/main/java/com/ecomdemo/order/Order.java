package com.ecomdemo.order;

import com.ecomdemo.customer.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A placed order.
 *
 * <p>The table is {@code orders}, not {@code order}: ORDER is a reserved word in SQL and an
 * unquoted {@code insert into order} is a syntax error.
 *
 * <p>Unlike the cart, {@code totalAmount} <em>is</em> stored. An order is a historical record:
 * it must still show what the customer actually paid years later, even after the catalogue
 * price has changed. For the same reason each line snapshots the product's name and price.
 *
 * <p>{@code @Enumerated(STRING)} stores "PLACED" rather than the ordinal 0 — reordering the
 * enum constants later would silently rewrite the meaning of existing rows.
 *
 * <p>Since Phase 8 an order also records <em>who</em> placed it. That single field is what makes
 * "my orders" answerable and what every authorization decision in {@code OrderService} is made
 * from. The foreign key is {@code ON DELETE RESTRICT} (V6): an order is a financial record and
 * must outlive attempts to tidy up the accounts table.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /**
     * Who placed it. LAZY: listing orders never needs the account row, because the query
     * already filters by its id, and {@link #getUsername()} is the only thing that reads it.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // required by JPA
    }

    public Order(Instant placedAt, User user) {
        this.placedAt = placedAt;
        this.user = user;
        this.status = OrderStatus.PLACED;
        this.totalAmount = BigDecimal.ZERO;
    }

    /** Adds a line and keeps the stored total consistent with it. */
    public void addItem(Long productId, String productName, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem(this, productId, productName, unitPrice, quantity);
        items.add(item);
        totalAmount = totalAmount.add(item.lineTotal());
    }

    public Long getId() {
        return id;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public User getUser() {
        return user;
    }

    /**
     * The owner's username, which is what the ownership check in {@code OrderService} compares
     * against {@code authentication.name}. Reading it initialises the lazy proxy, so it is
     * called while the order is still attached — inside {@code OrderResponse.from}, which runs
     * within the service's transaction.
     */
    public String getUsername() {
        return user.getUsername();
    }
}

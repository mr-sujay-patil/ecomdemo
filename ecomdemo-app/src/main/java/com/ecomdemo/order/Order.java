package com.ecomdemo.order;

import com.ecomdemo.order.internal.OrderService;
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
    /**
     * Who placed it, as an ID rather than an association — see {@code Cart.userId} for the full
     * reasoning. The account belongs to customer-service since Phase 20d, so the foreign key that
     * used to guarantee it existed is gone.
     *
     * <p>An order is the one place where that loss matters least: it already snapshots the username
     * into its audit row and the product name and price into its lines, precisely so that what was
     * charged cannot be changed by editing something else later.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * The owner's username, SNAPSHOTTED at checkout.
     *
     * <p>It used to be read through the association — {@code user.getUsername()} — which is exactly
     * the kind of thing a service boundary makes impossible. The name is a claim on the token now, so
     * it is copied onto the row when the order is placed, the same way the product name and the unit
     * price have been since Phase 6.
     *
     * <p><strong>The backfill had to happen in V15, while both tables were still in one
     * database.</strong> Once `users` is in customer-service there is no query that can populate this
     * column for orders that already exist — no join, no subselect, nothing but ten thousand HTTP
     * calls. That is the 20a lesson in its sharpest form: a data change that needs both halves has
     * exactly one window, and it closes when the services separate.
     */
    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // required by JPA
    }

    public Order(Instant placedAt, Long userId, String username) {
        this.placedAt = placedAt;
        this.userId = userId;
        this.username = username;
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

    public Long getUserId() {
        return userId;
    }

    /**
     * The owner's username, which is what the ownership check in {@code OrderService} compares
     * against {@code authentication.name}.
     *
     * <p>No lazy proxy to initialise any more, so the old warning about calling this only while the
     * order was still attached no longer applies — it is a column on this row.
     */
    public String getUsername() {
        return username;
    }
}

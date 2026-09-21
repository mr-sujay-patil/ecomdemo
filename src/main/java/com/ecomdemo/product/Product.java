package com.ecomdemo.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;

/**
 * A product in the catalogue.
 *
 * <p>Money is a {@link BigDecimal} mapped to {@code DECIMAL(12,2)}. A {@code double} cannot
 * represent 0.1 exactly, so repeated arithmetic on prices drifts by fractions of a cent;
 * BigDecimal stores an exact unscaled value and scale, and makes the rounding rule explicit.
 *
 * <p>JPA requires a no-arg constructor and a non-final class, which is why this is a normal
 * class and not a record: records are immutable and final, so Hibernate cannot proxy or
 * populate them.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /**
     * Optional catalogue category, added by Flyway migration V3.
     *
     * <p>Nullable on purpose. V3 adds the column to a table that already has rows and to a
     * system whose running instances know nothing about it, so a {@code NOT NULL} column would
     * have broken every insert from the old code during the deploy. Products created before the
     * migration - and any created without one since - simply have no category.
     */
    @Column(length = 50)
    private String category;

    /**
     * The optimistic lock, maintained by Hibernate and never touched by application code.
     *
     * <p>Every UPDATE of a product carries {@code AND version = ?} and raises the counter. If two
     * checkouts read the same product and both reduce its stock, the second UPDATE matches no row
     * and Hibernate throws {@code OptimisticLockException} (Spring translates it to
     * {@link org.springframework.dao.OptimisticLockingFailureException}) instead of quietly
     * overwriting the first one's change. That is the lost update the phase exists to prevent.
     *
     * <p>Optimistic, not pessimistic. No lock is taken and no transaction waits; the conflict is
     * detected at write time and the loser retries. That is the right trade when conflicts are
     * rare, and on a catalogue that is browsed far more often than it is sold from they are.
     * Pessimistic locking ({@code SELECT ... FOR UPDATE}) would instead serialise every checkout
     * of a popular product behind one row lock.
     *
     * <p>There is no getter on purpose. Nothing outside persistence has any business reading it,
     * and the tests that need to see it read the field or the column directly.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Product() {
        // required by JPA
    }

    public Product(String name, String description, BigDecimal price, int stockQuantity) {
        this(name, description, price, stockQuantity, null);
    }

    public Product(String name, String description, BigDecimal price, int stockQuantity,
            String category) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.category = category;
    }

    /**
     * Reduces stock by {@code quantity}.
     *
     * @throws IllegalArgumentException if the reduction would make stock negative; callers are
     *     expected to have checked availability first
     */
    public void reduceStock(int quantity) {
        if (quantity > stockQuantity) {
            throw new IllegalArgumentException(
                    "Cannot reduce stock of '%s' by %d: only %d available"
                            .formatted(name, quantity, stockQuantity));
        }
        this.stockQuantity -= quantity;
    }

    public boolean hasStockFor(int quantity) {
        return stockQuantity >= quantity;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(int stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}

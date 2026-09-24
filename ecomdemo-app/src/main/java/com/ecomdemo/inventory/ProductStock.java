package com.ecomdemo.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * How many of one product there are.
 *
 * <p><strong>This entity knows nothing about a product beyond its id</strong>, and that is the
 * whole reason it exists. Until Phase 20 the number lived on {@code Product}, so the inventory
 * module had to hold a catalogue entity to change it — an edge from inventory to catalog that
 * cannot survive the two becoming separate services. Here the id is just a number that arrived
 * from somewhere, which is exactly what it will be when it arrives over HTTP.
 *
 * <p><strong>The id is the product's, reused as the primary key.</strong> One stock row per
 * product is the invariant, and a primary key states it for free. There is no
 * {@code @GeneratedValue}: this row does not get to choose its own identity any more than a stock
 * count gets to choose which product it counts.
 *
 * <p><strong>The optimistic lock moved here from {@code Product}, and the move is a sharpening.</strong>
 * On the product row, {@code @Version} guarded every column at once: two concurrent checkouts
 * collided, correctly, and so did a checkout and an administrator editing the description —
 * a collision between two writes that had nothing to say to each other. The lock now sits on the
 * row that actually experiences contention. {@code ConcurrentCheckoutTest} still asserts the
 * collision that must happen; what disappears is the one that never needed to.
 */
@Entity
@Table(name = "product_stock")
public class ProductStock {

    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    /**
     * No getter, the same as {@code Product}'s was: nothing outside persistence has business
     * reading it, and the tests that need to see it read the column.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected ProductStock() {
        // for JPA
    }

    public ProductStock(Long productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    /**
     * Takes {@code quantity} out.
     *
     * @throws IllegalArgumentException if that would go negative; callers check availability first
     *     and get {@code InsufficientStockException} from {@link InventoryService} instead
     */
    void reduce(int amount) {
        if (amount > quantity) {
            throw new IllegalArgumentException(
                    "Cannot reduce stock of product %d by %d: only %d available"
                            .formatted(productId, amount, quantity));
        }
        this.quantity -= amount;
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    boolean has(int amount) {
        return quantity >= amount;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}

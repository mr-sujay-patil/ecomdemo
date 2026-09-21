package com.ecomdemo.cart;

import com.ecomdemo.product.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single shared cart. There are no users yet, so exactly one row of this table exists and
 * {@code CartService} always works with that row. Phase 8 introduces users and a cart per user.
 *
 * <p>{@code cascade = ALL} plus {@code orphanRemoval = true} makes the cart the owner of its
 * items' lifecycle: removing an item from this list deletes the row, so no CartItemRepository
 * is needed.
 */
@Entity
@Table(name = "cart")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    public Cart() {
        // a new cart starts empty
    }

    /** Adds the quantity to an existing line for this product, or creates a new line. */
    public void addItem(Product product, int quantity) {
        findItem(product.getId())
                .ifPresentOrElse(
                        item -> item.increaseQuantity(quantity),
                        () -> items.add(new CartItem(this, product, quantity)));
    }

    public void updateQuantity(CartItem item, int quantity) {
        item.setQuantity(quantity);
    }

    public void removeItem(CartItem item) {
        items.remove(item);
    }

    public void clear() {
        items.clear();
    }

    public Optional<CartItem> findItem(Long productId) {
        return items.stream()
                .filter(item -> item.getProduct().getId().equals(productId))
                .findFirst();
    }

    /**
     * The total is always computed from the lines, never stored. A stored total is a second
     * source of truth that silently goes stale when a price or quantity changes.
     */
    public BigDecimal total() {
        return items.stream()
                .map(CartItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Long getId() {
        return id;
    }

    public List<CartItem> getItems() {
        return items;
    }
}

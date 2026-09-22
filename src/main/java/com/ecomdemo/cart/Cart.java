package com.ecomdemo.cart;

import com.ecomdemo.cart.internal.CartRepository;
import com.ecomdemo.customer.User;
import com.ecomdemo.catalog.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One shopper's cart.
 *
 * <p>Until Phase 8 there was exactly one row in this table and everybody added to it. The
 * {@link #user} below is the whole difference: {@code CartService} now finds the cart by the
 * authenticated account, and the {@code uq_cart_user} constraint from V6 makes "one cart per
 * account" a rule the database enforces rather than an assumption the code makes.
 *
 * <p>The association is {@code @ManyToOne} even though there is only ever one cart per user,
 * because the cardinality that matters to JPA is which side holds the foreign key — {@code cart}
 * does, in {@code user_id}. The uniqueness is expressed by the constraint, not by using
 * {@code @OneToOne}, which would additionally make Hibernate want to load a cart whenever a user
 * is loaded.
 *
 * <p>{@code cascade = ALL} plus {@code orphanRemoval = true} makes the cart the owner of its
 * items' lifecycle: removing an item from this list deletes the row, so no CartItemRepository
 * is needed. Note that the cascade stops at the items — it does not reach the user, who exists
 * quite independently of any cart.
 */
@Entity
@Table(name = "cart")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The owner. LAZY because almost nothing that reads a cart needs the account behind it —
     * the queries in {@code CartRepository} filter on {@code user_id} rather than dereferencing
     * this, so the row is usually never fetched at all.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
        // required by JPA
    }

    /** A new cart starts empty and belongs to someone from the moment it exists. */
    public Cart(User user) {
        this.user = user;
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

    public User getUser() {
        return user;
    }

    public List<CartItem> getItems() {
        return items;
    }
}

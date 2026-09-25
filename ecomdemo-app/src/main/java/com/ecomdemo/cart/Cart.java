package com.ecomdemo.cart;

import com.ecomdemo.cart.internal.CartRepository;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
     * The owner, as an ID rather than an association.
     *
     * <p>It was {@code @ManyToOne(fetch = LAZY) User} until Phase 20d, with a real foreign key. The
     * account is customer-service's now, so there is nothing in this database for a key to point at
     * and nothing for Hibernate to lazily load.
     *
     * <p>THE COLUMN DID NOT CHANGE. It has always been {@code user_id}, and the queries in
     * {@code CartRepository} already filtered on it rather than dereferencing the association — the
     * old comment here said so. So this is a mapping change and a dropped constraint, not a data
     * change, which is the whole reason it is safe to do while there is still one deployable.
     *
     * <p><strong>What is lost is real.</strong> The foreign key guaranteed that a cart belonged to
     * an account that existed; nothing guarantees that now, so deleting an account leaves its cart
     * behind. That is the same trade 20a made for {@code cart_item.product_id}, and it is the price
     * of a database per service. Recorded in {@code docs/decisions.md}.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
        // required by JPA
    }

    /** A new cart starts empty and belongs to someone from the moment it exists. */
    public Cart(Long userId) {
        this.userId = userId;
    }

    /** Adds the quantity to an existing line for this product, or creates a new line. */
    /**
     * Adds a line, or increases the one already there.
     *
     * <p>Takes the product's values rather than a {@code Product} since Phase 20: this entity no
     * longer holds an association to the catalogue, and passing one in would only put it back.
     * {@code CartService} does the lookup and hands over what it found.
     *
     * <p>Note what increasing an existing line does NOT do: it does not refresh the remembered
     * price. Adding a second unit of something already in the cart keeps the price the first unit
     * was added at, which is the same rule the rest of this change follows.
     */
    public void addItem(Long productId, String productName, BigDecimal unitPrice, int quantity) {
        findItem(productId)
                .ifPresentOrElse(
                        item -> item.increaseQuantity(quantity),
                        () -> items.add(
                                new CartItem(this, productId, productName, unitPrice, quantity)));
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
                .filter(item -> item.getProductId().equals(productId))
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

    public Long getUserId() {
        return userId;
    }

    public List<CartItem> getItems() {
        return items;
    }
}

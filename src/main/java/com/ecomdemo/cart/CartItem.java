package com.ecomdemo.cart;

import com.ecomdemo.cart.internal.CartRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One line of the cart: a product and how many of it.
 *
 * <p>Both associations are {@code LAZY}. JPA's default for {@code @ManyToOne} is EAGER, which
 * means every load of a cart item fires an extra query for its product whether the caller needs
 * it or not. Declaring LAZY and then asking for the data explicitly (see
 * {@code CartRepository#findCart}, which uses a JOIN FETCH) keeps the query count predictable
 * and avoids the N+1 problem.
 */
@Entity
@Table(name = "cart_item")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    /**
     * The product, as three remembered values rather than an association.
     *
     * <p>Until Phase 20 this was {@code @ManyToOne Product} with a real foreign key. The cart is
     * going to order-service and the product to catalog-service, with a database each, so the
     * constraint has nothing to point at and the association has nothing to load.
     *
     * <p>{@code order_item} has looked exactly like this since Phase 6, written that way so an
     * order would not silently reprice when the catalogue was edited. That decision turns out to
     * be what a service boundary requires as well.
     */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    private int quantity;

    protected CartItem() {
        // required by JPA
    }

    CartItem(Cart cart, Long productId, String productName, BigDecimal unitPrice, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    void increaseQuantity(int amount) {
        this.quantity += amount;
    }

    /**
     * Priced from the snapshot, so the cart reflects the catalogue as at the moment this line was
     * added rather than as it is now.
     *
     * <p>That is a real change from Phase 19 and it is the trade a distributed system makes: a
     * cart that repriced itself would need a call to the catalogue service on every read of the
     * hottest path there is. It is also arguably the more honest behaviour, since the price a
     * shopper was shown is the price they expect at checkout.
     */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}

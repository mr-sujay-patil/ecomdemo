package com.ecomdemo.cart;

import com.ecomdemo.product.Product;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    private int quantity;

    protected CartItem() {
        // required by JPA
    }

    CartItem(Cart cart, Product product, int quantity) {
        this.cart = cart;
        this.product = product;
        this.quantity = quantity;
    }

    void increaseQuantity(int amount) {
        this.quantity += amount;
    }

    /** Priced from the product's current price: the cart reflects today's catalogue. */
    public BigDecimal lineTotal() {
        return product.getPrice().multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}

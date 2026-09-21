package com.ecomdemo.support;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.product.Product;
import java.math.BigDecimal;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for the entities the tests need.
 *
 * <p>Ids are normally assigned by the database ({@code @GeneratedValue}), so an entity built
 * with {@code new} in a unit test has {@code id == null}. Several rules depend on the id —
 * {@link Cart#findItem(Long)} matches lines by product id — so the tests have to set it.
 * There is deliberately no {@code setId()} on the entities: production code must never invent
 * an id, and adding a setter just to please the tests would weaken the model. Writing the
 * field reflectively keeps that pressure off the production classes and is exactly what
 * {@link ReflectionTestUtils} exists for.
 */
public final class TestData {

    private TestData() {
    }

    /** A product with a fixed id, as if it had already been saved. */
    public static Product product(long id, String name, String price, int stockQuantity) {
        Product product = new Product(name, name + " description", new BigDecimal(price), stockQuantity);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    /** An empty cart with a fixed id. */
    public static Cart cart(long id) {
        Cart cart = new Cart();
        ReflectionTestUtils.setField(cart, "id", id);
        return cart;
    }

    /** A cart holding one line: {@code quantity} of {@code product}. */
    public static Cart cartWith(long id, Product product, int quantity) {
        Cart cart = cart(id);
        cart.addItem(product, quantity);
        return cart;
    }
}

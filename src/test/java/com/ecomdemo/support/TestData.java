package com.ecomdemo.support;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.User;
import com.ecomdemo.order.Order;
import com.ecomdemo.catalog.Product;
import java.math.BigDecimal;
import java.time.Instant;
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
    /**
     * A catalogue product with a fixed id.
     *
     * <p><strong>The stock argument is kept and ignored since Phase 20</strong>, deliberately.
     * Stock left {@code Product} for {@code product_stock}, so there is no field here to set - but
     * dozens of tests read as "a lamp, nine in stock", and rewriting every call site to drop the
     * number would lose the only readable statement of what the scenario is about. Tests that
     * actually depend on the quantity stub {@code InventoryService} for it; the rest are
     * documenting intent, which this parameter still does.
     */
    public static Product product(long id, String name, String price, int stockQuantity) {
        Product product = new Product(name, name + " description", new BigDecimal(price));
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    /**
     * A user with a fixed id and a fixed hash.
     *
     * <p>The "hash" is not a real BCrypt hash and does not need to be. Nothing in a unit test
     * verifies a password: {@code PasswordEncoder} is either mocked or, in the slice tests,
     * bypassed entirely by {@code @WithMockUser}. A string that could never be produced by
     * BCrypt is better than a plausible one, because it cannot be mistaken for a working
     * credential if it ever escapes into a log.
     */
    public static User user(long id, String username, Role role) {
        User user = new User(username, "{not-a-real-hash}", username + " the " + role, role,
                Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** The usual shopper: id 1, username "customer". */
    public static User customer() {
        return user(1L, "customer", Role.CUSTOMER);
    }

    /** The usual shopkeeper: id 2, username "admin". */
    public static User admin() {
        return user(2L, "admin", Role.ADMIN);
    }

    /** An empty cart with a fixed id, belonging to {@link #customer()}. */
    public static Cart cart(long id) {
        return cart(id, customer());
    }

    /** An empty cart with a fixed id, belonging to a given user. */
    public static Cart cart(long id, User owner) {
        Cart cart = new Cart(owner);
        ReflectionTestUtils.setField(cart, "id", id);
        return cart;
    }

    /** A cart holding one line: {@code quantity} of {@code product}. */
    public static Cart cartWith(long id, Product product, int quantity) {
        Cart cart = cart(id);
        cart.addItem(product, quantity);
        return cart;
    }

    /** An order with a fixed id, placed now by {@link #customer()}. */
    public static Order order(long id) {
        return order(id, customer());
    }

    /** An order with a fixed id, placed now by a given user. */
    public static Order order(long id, User placedBy) {
        Order order = new Order(Instant.parse("2026-01-01T00:00:00Z"), placedBy);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}

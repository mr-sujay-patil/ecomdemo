package com.ecomdemo.support;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.support.TestAccount;
import com.ecomdemo.order.Order;
import com.ecomdemo.clients.catalog.ProductSnapshot;
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

    /**
     * A product with a fixed id, as if the catalogue had returned it.
     *
     * <p>It is a {@code ProductSnapshot} rather than a {@code ProductSnapshot} entity, and the
     * {@code stockQuantity} parameter is gone. Both changes are Phase 20c: the entity belongs to
     * catalog-service now, and this module can only ever hold what a catalogue call returns. The
     * quantity had already stopped meaning anything in 20a and was still being passed — a
     * parameter that documents intent and changes nothing reads like a setup step.
     */
    /**
     * A catalogue product with a fixed id.
     *
     * <p><strong>The stock argument is kept and ignored since Phase 20</strong>, deliberately.
     * Stock left {@code ProductSnapshot} for {@code product_stock}, so there is no field here to set - but
     * dozens of tests read as "a lamp, nine in stock", and rewriting every call site to drop the
     * number would lose the only readable statement of what the scenario is about. Tests that
     * actually depend on the quantity stub {@code InventoryService} for it; the rest are
     * documenting intent, which this parameter still does.
     */
    public static ProductSnapshot product(long id, String name, String price) {
        return new ProductSnapshot(id, name, name + " description", new BigDecimal(price), null, 0);
    }

    /**
     * Who a test is acting as.
     *
     * <p>These built a {@code TestAccount} ENTITY until Phase 20d, complete with a fake BCrypt hash and a
     * created-at timestamp. The entity belongs to customer-service now and this module cannot
     * construct one — so what comes back is a {@link TestAccount}: an id, a name and a role, which is
     * everything a token carries and everything cart and order store.
     *
     * <p>The fake hash is gone with it, and that is a small improvement on its own. Nothing here ever
     * verified a password, so a plausible-looking credential in test data was a liability with no
     * purpose.
     */
    public static TestAccount user(long id, String username, String role) {
        return new TestAccount(id, username, role);
    }

    /** The usual shopper: id 1, username "customer". */
    public static TestAccount customer() {
        return TestAccount.customer(1L, "customer");
    }

    /** The usual shopkeeper: id 2, username "admin". */
    public static TestAccount admin() {
        return TestAccount.admin(2L, "admin");
    }

    /** An empty cart with a fixed id, belonging to {@link #customer()}. */
    public static Cart cart(long id) {
        return cart(id, customer());
    }

    /**
     * An empty cart with a fixed id, belonging to a given user.
     *
     * <p>It still TAKES a {@code TestAccount} even though the cart now stores only an id, and that is
     * deliberate: the call sites read {@code cart(1L, customer())}, which says who owns it far better
     * than a bare number would. The narrowing happens here, in one place.
     */
    public static Cart cart(long id, TestAccount owner) {
        Cart cart = new Cart(owner.id());
        ReflectionTestUtils.setField(cart, "id", id);
        return cart;
    }

    /**
     * A cart holding one line: {@code quantity} of {@code product}.
     *
     * <p>Still takes a {@code ProductSnapshot} for readability at the call sites, and snapshots it the way
     * {@code CartService} does. Since Phase 20 the cart stores remembered values rather than an
     * association, so what a test builds here is a line that will NOT follow a later price change
     * - which is the behaviour under test in {@code CartServiceTest}.
     */
    public static Cart cartWith(long id, ProductSnapshot product, int quantity) {
        Cart cart = cart(id);
        addTo(cart, product, quantity);
        return cart;
    }

    /** Adds a product to a cart the way {@code CartService} does: as a snapshot. */
    public static void addTo(Cart cart, ProductSnapshot product, int quantity) {
        cart.addItem(product.id(), product.name(), product.price(), quantity);
    }

    /** An order with a fixed id, placed now by {@link #customer()}. */
    public static Order order(long id) {
        return order(id, customer());
    }

    /** An order with a fixed id, placed now by a given user. See {@link #cart(long, TestAccount)} on why
     * this still takes a {@code TestAccount}. */
    public static Order order(long id, TestAccount placedBy) {
        Order order = new Order(
                Instant.parse("2026-01-01T00:00:00Z"), placedBy.id(), placedBy.username());
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}

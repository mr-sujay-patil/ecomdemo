package com.ecomdemo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.User;
import com.ecomdemo.customer.UserRepository;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.TestAuthentication;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The "Done when" of Phase 6: two checkouts race for the last unit, exactly one wins, and the
 * one that loses leaves nothing behind.
 *
 * <p>This is the only test in the suite that needs real threads, and it needs them for a reason
 * no mock can fake. The bug it guards against is not in any single method — every step of
 * {@code placeOnce()} is correct on its own. It exists only in the interleaving: two transactions
 * read stock 1, both conclude there is enough, and both write 0. Proving it is gone means running
 * the interleaving.
 *
 * <p>The test is deliberately NOT {@code @Transactional}. A test transaction would wrap both
 * threads' work in one unit and roll it back at the end, which would mean the two checkouts never
 * commit against each other and there is no race left to lose.
 *
 * <p>Nothing rolls it back, so it tidies up after itself instead. Every {@code @SpringBootTest}
 * in the suite shares one application context and therefore one H2 database, and rows left here
 * are rows the next test class finds. Each test empties the shared cart before it starts, scopes
 * its assertions to the products it created, and deletes those products afterwards.
 */
@SpringBootTest
class ConcurrentCheckoutTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderAuditRepository orderAuditRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * The shopper both racing threads act as. They share one account deliberately: the race is
     * two checkouts of the SAME cart, which is only possible if they are the same person — two
     * different accounts now have two different carts and could not collide at all.
     */
    private User shopper;

    /** Every product this class creates is named with it, so the cleanup can find them all. */
    private static final String PRODUCT_PREFIX = "Concurrency ";

    /**
     * The cart is one row per account and this test class does not roll anything back, so it
     * starts from a known state instead of assuming one.
     */
    @BeforeEach
    void signInAndEmptyTheCart() {
        shopper = TestAuthentication.account(userRepository, "race-test-shopper", Role.CUSTOMER);
        TestAuthentication.authenticateAs(shopper);
        emptyTheCart();
    }

    private void emptyTheCart() {
        cartService.view().items().stream()
                .map(CartItemResponse::productId)
                .forEach(cartService::removeItem);
    }

    /**
     * Leaves the catalogue as it was found. The cart is emptied first because {@code cart_item}
     * has a {@code RESTRICT} foreign key to {@code product}: a product somebody is holding cannot
     * be deleted, which is exactly what V1 intended and what a half-finished test would hit.
     * Order lines keep their own copy of the name and price and have no foreign key, so history
     * survives the delete.
     */
    @AfterEach
    void deleteTheProductsThisTestCreated() {
        emptyTheCart();
        productService.findAll().stream()
                .filter(product -> product.name().startsWith(PRODUCT_PREFIX))
                .forEach(product -> productService.delete(product.id()));
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("two threads buy the last unit: exactly one succeeds, the other gets a 409")
    void twoThreadsBuyingTheLastUnit_leaveExactlyOneOrderAndZeroStock() {
        ProductResponse lastUnit = create(PRODUCT_PREFIX + "Widget", 1);
        cartService.addItem(new AddCartItemRequest(lastUnit.id(), 1));

        List<Outcome> outcomes = placeTwiceAtOnce();

        // Exactly one, not "at least one": overselling would show up here as two successes.
        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).singleElement()
                .satisfies(o -> assertThat(o.failure()).isInstanceOf(ConflictException.class));

        assertThat(productService.findById(lastUnit.id()).stockQuantity())
                .as("the one unit was sold once, not twice")
                .isZero();
        assertThat(ordersContaining(lastUnit.id()))
                .as("one order, for one unit")
                .singleElement()
                .satisfies(line -> assertThat(line.quantity()).isEqualTo(1));
        assertThat(cartService.view().items()).isEmpty();
    }

    @Test
    @DisplayName("the checkout that loses the race rolls back the stock it had already reduced")
    void theLosingCheckoutLeavesNoPartialData() {
        // Two lines. The loser gets as far as writing the plentiful line's new stock before the
        // scarce line's versioned UPDATE is rejected - so if the transaction were not atomic,
        // 'Plentiful' would end up 4 short instead of 2.
        ProductResponse plentiful = create(PRODUCT_PREFIX + "Bulk Item", 10);
        ProductResponse scarce = create(PRODUCT_PREFIX + "Scarce Item", 1);
        cartService.addItem(new AddCartItemRequest(plentiful.id(), 2));
        cartService.addItem(new AddCartItemRequest(scarce.id(), 1));

        List<Outcome> outcomes = placeTwiceAtOnce();

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(productService.findById(plentiful.id()).stockQuantity())
                .as("reduced by the winner's 2, and by nothing else")
                .isEqualTo(8);
        assertThat(productService.findById(scarce.id()).stockQuantity()).isZero();
        assertThat(ordersContaining(scarce.id())).hasSize(1);
        assertThat(ordersContaining(plentiful.id()))
                .as("the loser's order lines were rolled back with the rest of its transaction")
                .hasSize(1);
    }

    @Test
    @DisplayName("a rejected checkout is audited even though its transaction rolled back")
    void aRejectedCheckoutStillLeavesAnAuditRow() {
        // No threads needed for this one: an empty cart is refused, the transaction rolls back,
        // and the audit row written with REQUIRES_NEW is the only thing that survives it.
        long before = orderAuditRepository.findByOutcomeOrderByIdDesc(OrderOutcome.REJECTED).size();

        assertThatThrownBy(() -> orderService.place())
                .isInstanceOf(ConflictException.class)
                .hasMessage("Cannot place an order: the cart is empty");

        List<OrderAudit> rejections =
                orderAuditRepository.findByOutcomeOrderByIdDesc(OrderOutcome.REJECTED);
        assertThat(rejections).hasSize((int) before + 1);
        assertThat(rejections.getFirst()).satisfies(audit -> {
            assertThat(audit.getDetail()).isEqualTo("Cannot place an order: the cart is empty");
            assertThat(audit.getOrderId()).as("there is no order to point at").isNull();
            assertThat(audit.getRecordedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a successful checkout is audited with the id of the order it created")
    void aSuccessfulCheckoutIsAuditedAgainstItsOrder() {
        ProductResponse product = create(PRODUCT_PREFIX + "Audited Item", 5);
        cartService.addItem(new AddCartItemRequest(product.id(), 2));

        OrderResponse placed = orderService.place();

        assertThat(orderAuditRepository.findByOutcomeOrderByIdDesc(OrderOutcome.PLACED).getFirst())
                .satisfies(audit -> {
                    assertThat(audit.getOrderId()).isEqualTo(placed.id());
                    assertThat(audit.getDetail()).contains("1 line(s)");
                });
    }

    /**
     * Runs two checkouts of the shared cart as close to simultaneously as two threads can manage.
     *
     * <p>The latch is what makes the overlap likely rather than accidental: both threads are
     * started, both block on it, and only then are they released together. Without it the first
     * thread would usually be finished before the second was scheduled, and the test would pass
     * without ever exercising the race.
     */
    private List<Outcome> placeTwiceAtOnce() {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> checkout = () -> {
                // SecurityContextHolder is a ThreadLocal, so each worker thread starts with an
                // empty context and has to sign in for itself. Skipping this is how a race test
                // fails with "no authenticated user" instead of with the race it meant to test.
                TestAuthentication.authenticateAs(shopper);
                start.await(5, TimeUnit.SECONDS);
                try {
                    return new Outcome(orderService.place(), null);
                } catch (RuntimeException ex) {
                    return new Outcome(null, ex);
                }
            };
            List<Future<Outcome>> futures = List.of(pool.submit(checkout), pool.submit(checkout));
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception ex) {
                    throw new IllegalStateException("a checkout thread did not finish", ex);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private ProductResponse create(String name, int stock) {
        return productService.create(
                new ProductRequest(name, "Created by " + getClass().getSimpleName(),
                        new BigDecimal("10.00"), stock, "TEST"));
    }

    /** Every order line in the database for one product, across all orders. */
    private List<OrderItemResponse> ordersContaining(Long productId) {
        return orderService.findAll().stream()
                .flatMap(order -> order.items().stream())
                .filter(line -> line.productId().equals(productId))
                .toList();
    }

    /** What one checkout thread came back with: an order, or the exception that stopped it. */
    private record Outcome(OrderResponse order, RuntimeException failure) {

        boolean succeeded() {
            return order != null;
        }
    }
}

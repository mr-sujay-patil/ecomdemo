package com.ecomdemo.order.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.messaging.OrderCreatedEvent;
import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.support.InMemoryCatalogConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.ecomdemo.shared.InsufficientStockException;
import com.ecomdemo.clients.inventory.InventoryClient;
import com.ecomdemo.clients.catalog.ProductWrite;
import com.ecomdemo.clients.catalog.ProductSnapshot;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

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
@Import(InMemoryCatalogConfig.class)
class ConcurrentCheckoutTest {

    private static final long SHOPPER_ID = 4101L;
    private static final String SHOPPER_USERNAME = "race-test-shopper";

    /**
     * <strong>Mocked since Phase 20b, and the mock is the honest choice rather than a shortcut.</strong>
     *
     * <p>Stock lives in inventory-service now. Creating a product PUTs a level over HTTP, and
     * checkout reserves over HTTP — so without this every test here fails with a
     * {@code ConnectException} to a service that is not part of this application.
     *
     * <p>What matters is what that costs. These tests can no longer say anything about stock
     * ARITHMETIC, and they should not pretend to: {@code InventoryServiceTest} and
     * {@code ConcurrentReservationTest} cover that, against a real database, in the service that
     * owns it. What is left here is ordering and auditing, which is what this class was always
     * really about. (Phase 20b added a compensation test here; Phase 24's saga replaced that
     * compensation, and the test now asserts what replaced it - see below.)
     */
    @MockitoBean
    private InventoryClient inventory;

    @Autowired
    private CatalogGateway catalogue;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderAuditRepository orderAuditRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * A SPY, not a mock (Phase 24): every checkout here writes its OrderCreatedEvent for real, and
     * one test makes that write fail to show what a late failure leaves behind.
     */
    @MockitoSpyBean
    private OutboxWriter outboxWriter;

    /**
     * The shopper both racing threads act as. They share one account deliberately: the race is
     * two checkouts of the SAME cart, which is only possible if they are the same person — two
     * different accounts now have two different carts and could not collide at all.
     */

    /** Every product this class creates is named with it, so the cleanup can find them all. */
    private static final String PRODUCT_PREFIX = "Concurrency ";

    /**
     * The cart is one row per account and this test class does not roll anything back, so it
     * starts from a known state instead of assuming one.
     */
    @BeforeEach
    void signInAndEmptyTheCart() {
        // An id and a name, not an account row: this application has no `users` table since Phase
        // 20d. The id is what cart and order store, and it is all they need.
        TestAuthentication.authenticateAs(SHOPPER_ID, SHOPPER_USERNAME);
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
        catalogue.findAll().stream()
                .filter(product -> product.name().startsWith(PRODUCT_PREFIX))
                .forEach(product -> catalogue.delete(product.id()));
        TestAuthentication.clear();
    }

    // REMOVED in Phase 20b: "two threads buy the last unit: exactly one succeeds".
    //
    // The assertion was about an optimistic lock on a stock row, and that row is in another
    // service's database now - this test reaches it over HTTP and cannot create the race at all.
    // The coverage did not disappear: it is ConcurrentReservationTest in inventory-service, which
    // runs the same two threads against a real PostgreSQL container.
    //
    // Deleted rather than weakened. A version of this test with a mocked client would asserted
    // that two threads can both call a mock, which is true of any mock and proves nothing about
    // overselling.

    @Test
    @DisplayName("a checkout refused by the stock pre-check takes nothing and releases nothing")
    void aRefusedCheckoutReleasesNothing() {
        // Phase 20b's "most important test", when checkout reserved over HTTP and compensated with
        // InventoryClient.release on rollback. Its assertion still stands, for a simpler reason
        // since Phase 24: checkout never reserves anything at all (the saga does, in
        // inventory-service), so a refused checkout has nothing to give back - and a release here
        // would put stock into existence.
        ProductSnapshot plentiful = create(PRODUCT_PREFIX + "Bulk Item", 10);
        ProductSnapshot scarce = create(PRODUCT_PREFIX + "Scarce Item", 1);
        cartService.addItem(new AddCartItemRequest(plentiful.id(), 2));
        cartService.addItem(new AddCartItemRequest(scarce.id(), 1));

        // The second line fails its availability check, so checkout throws before writing anything.
        doThrow(new InsufficientStockException("Scarce Item", 1, 0))
                .when(inventory).requireAvailable(eq(scarce.id()), anyString(), anyInt());

        assertThatThrownBy(() -> orderService.place())
                .isInstanceOf(ConflictException.class);

        // Nothing was reserved and nothing is released.
        verify(inventory, never()).reserve(anyLong(), anyString(), anyInt());
        verify(inventory, never()).release(anyLong(), anyInt());
    }

    @Test
    @DisplayName("a checkout that fails AFTER writing its lines leaves no order and no saga event behind")
    void aLateFailureLeavesNothingToCompensate() {
        // REWRITTEN in Phase 24. This test was "a rollback AFTER reserving releases every line
        // that was taken": it failed the second HTTP reservation and verified release() for the
        // first. That compensation no longer exists, because the thing it undid no longer happens -
        // checkout reserves nothing over HTTP; it writes an OrderCreatedEvent to the outbox and
        // inventory-service reserves when it reads it.
        //
        // So the guarantee this test protects has CHANGED SHAPE, not disappeared. It used to be
        // "if checkout fails late, we ask for the stock back". It is now "if checkout fails late,
        // inventory never hears of the order at all" - because the order, the emptied cart and the
        // saga's first event are ONE local transaction. That is stronger: there is no remote write
        // inside it, so there is nothing that can be left behind for a lost compensation to miss.
        //
        // The failure is injected at the last step, the outbox write, after the order has been
        // saved and the cart emptied - the latest point a checkout can fail.
        ProductSnapshot first = create(PRODUCT_PREFIX + "First", 10);
        ProductSnapshot second = create(PRODUCT_PREFIX + "Second", 10);
        cartService.addItem(new AddCartItemRequest(first.id(), 2));
        cartService.addItem(new AddCartItemRequest(second.id(), 3));
        int ordersBefore = orderRepository.findAllByUserIdWithItems(SHOPPER_ID).size();
        long sagaEventsBefore = sagaEventsStarted();

        // Stubbed on the proxy's TARGET: OutboxWriter is @Transactional(MANDATORY), and stubbing
        // through the proxy would run that check - with no transaction open - before Mockito ever
        // saw the call.
        OutboxWriter target = AopTestUtils.getUltimateTargetObject(outboxWriter);
        doThrow(new IllegalStateException("the outbox write fell over mid-checkout"))
                .when(target).append(any(OrderCreatedEvent.class));

        assertThatThrownBy(() -> orderService.place()).isInstanceOf(RuntimeException.class);

        // No order, no event - so inventory-service has nothing to reserve and nothing to undo.
        assertThat(orderRepository.findAllByUserIdWithItems(SHOPPER_ID)).hasSize(ordersBefore);
        assertThat(sagaEventsStarted()).isEqualTo(sagaEventsBefore);
        // The emptied cart came back with the rollback: the shopper can simply try again.
        assertThat(cartService.view().items()).hasSize(2);
        // And, as before the saga as after it, no stock was released that was never taken.
        verify(inventory, never()).reserve(anyLong(), anyString(), anyInt());
        verify(inventory, never()).release(anyLong(), anyInt());
    }

    private long sagaEventsStarted() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'OrderCreatedEvent'", Long.class);
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
        ProductSnapshot product = create(PRODUCT_PREFIX + "Audited Item", 5);
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
                TestAuthentication.authenticateAs(SHOPPER_ID, SHOPPER_USERNAME);
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

    private ProductSnapshot create(String name, int stock) {
        return catalogue.create(
                new ProductWrite(name, "Created by " + getClass().getSimpleName(),
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

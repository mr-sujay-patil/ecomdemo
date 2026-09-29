package com.ecomdemo.order.internal.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.ProductWrite;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.order.dto.OrderStatusResponse;
import com.ecomdemo.support.FakeSagaParticipants;
import com.ecomdemo.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 32's "done when", as an automated test: an order whose saga event is lost - dead-lettered -
 * ends CONFIRMED or CANCELLED, with its stock released, once the deadline passes.
 *
 * <p>Everything on the order service's side is real: the checkout, the outbox, Kafka, the saga
 * listeners, PostgreSQL, the sweeper and the reconciler. The other two services are
 * {@link FakeSagaParticipants}, told to lose one particular message for one product, and answering
 * the reconciler with the real services' rules (settling an unpaid order voids it; closing an order
 * gives its stock back and fences it).
 *
 * <p>The sweep is called with a clock rather than waited for: {@code sweep(now + deadline)} asks
 * "what does the sweep do once this order is overdue?" without the test sleeping for a minute. The
 * scheduled sweep is switched off in the {@code it} profile so nothing else decides these orders.
 */
class SagaDeadlineIT extends IntegrationTest {

    @Autowired
    private SagaDeadlineSweeper sweeper;

    @Autowired
    private SagaProperties properties;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> lostFor = new ArrayList<>();
    private TestRestTemplate shopper;

    @BeforeEach
    void signIn() {
        shopper = asCustomer("it-saga-deadline-shopper");
        CartResponse cart = shopper.getForObject("/api/cart", CartResponse.class);
        cart.items().forEach(item -> shopper.delete("/api/cart/items/" + item.productId()));
    }

    @AfterEach
    void stopLosingMessages() {
        lostFor.forEach(FakeSagaParticipants.LOSE_ORDER_CREATED::remove);
        lostFor.forEach(FakeSagaParticipants.LOSE_STOCK_RESERVED::remove);
        lostFor.forEach(FakeSagaParticipants.LOSE_PAYMENT_REPLY::remove);
        FakeSagaParticipants.PAYMENT_UNREACHABLE.set(false);
    }

    /** A product whose saga will lose the given message, and an order for 2 of it (stock 5). */
    private Checkout checkoutLosing(java.util.Set<Long> lose, String name) {
        ProductSnapshot product = catalogue.create(
                new ProductWrite(name, name + " description", new BigDecimal("20.00"), 5, "IT"));
        lose.add(product.id());
        lostFor.add(product.id());

        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(product.id(), 2), CartResponse.class);
        ResponseEntity<OrderResponse> placed = shopper.postForEntity("/api/orders", null, OrderResponse.class);
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = placed.getBody().id();

        // The fake has read the OrderCreated - and lost what it was told to lose.
        await().atMost(Duration.ofSeconds(30)).until(() -> FakeSagaParticipants.SEEN.contains(orderId));
        return new Checkout(orderId, product.id());
    }

    private record Checkout(long orderId, long productId) {}

    private OrderStatusResponse status(long orderId) {
        return shopper.getForObject("/api/orders/" + orderId + "/status", OrderStatusResponse.class);
    }

    private int stockOf(long productId) {
        return catalogue.requireProduct(productId).stockQuantity();
    }

    /** A sweep run as if the deadline had just passed for everything placed until now. */
    private void sweepAfterTheDeadline() {
        sweeper.sweep(Instant.now().plus(properties.deadline()).plusSeconds(1));
    }

    private double reconciliations(String outcome) {
        var counter = meters.find(SagaMetrics.RECONCILIATIONS).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("StockReserved dead-lettered: stock held, nobody paid → CANCELLED and the stock comes back")
    void stockHeldButNeverPaid() {
        Checkout order = checkoutLosing(FakeSagaParticipants.LOSE_STOCK_RESERVED, "IT Deadline Held");
        assertThat(stockOf(order.productId())).as("held by the reservation").isEqualTo(3);

        sweeper.sweep(Instant.now());   // not overdue yet: left alone
        assertThat(status(order.orderId()).status()).isEqualTo(OrderStatus.PENDING);

        double cancelledBefore = reconciliations("cancelled");
        sweepAfterTheDeadline();

        OrderStatusResponse decided = status(order.orderId());
        assertThat(decided.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(decided.reason()).isEqualTo(
                "Payment was not attempted before the order's deadline; the order was cancelled");
        assertThat(stockOf(order.productId())).as("released by the close").isEqualTo(5);
        assertThat(reconciliations("cancelled")).isEqualTo(cancelledBefore + 1);
    }

    @Test
    @DisplayName("payment reply dead-lettered: the customer WAS charged → CONFIRMED, never cancelled")
    void paidButNeverTold() {
        Checkout order = checkoutLosing(FakeSagaParticipants.LOSE_PAYMENT_REPLY, "IT Deadline Paid");

        sweepAfterTheDeadline();

        assertThat(status(order.orderId()).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stockOf(order.productId())).as("sold, so not given back").isEqualTo(3);
        // Confirmed by the deadline exactly as a PaymentCompleted would have confirmed it: the
        // shopper is thanked, through the same OrderPlaced event.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'OrderPlacedEvent'",
                        Integer.class, String.valueOf(order.orderId())))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("OrderCreated dead-lettered: nothing happened anywhere → CANCELLED, stock untouched")
    void neverStarted() {
        Checkout order = checkoutLosing(FakeSagaParticipants.LOSE_ORDER_CREATED, "IT Deadline Lost");

        sweepAfterTheDeadline();

        assertThat(status(order.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(order.productId())).isEqualTo(5);
    }

    @Test
    @DisplayName("payment-service does not answer: UNKNOWN outcome, so nothing is decided until it does")
    void unknownOutcomeIsNotGuessed() {
        Checkout order = checkoutLosing(FakeSagaParticipants.LOSE_STOCK_RESERVED, "IT Deadline Unknown");
        FakeSagaParticipants.PAYMENT_UNREACHABLE.set(true);

        double deferredBefore = reconciliations("deferred");
        sweepAfterTheDeadline();

        assertThat(status(order.orderId()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(stockOf(order.productId())).as("still held: nothing was decided").isEqualTo(3);
        assertThat(reconciliations("deferred")).isGreaterThan(deferredBefore);
        assertThat(meters.get(SagaMetrics.OVERDUE).gauge().value())
                .as("the stuck-order gauge shows it")
                .isGreaterThanOrEqualTo(1);

        FakeSagaParticipants.PAYMENT_UNREACHABLE.set(false);
        sweepAfterTheDeadline();

        assertThat(status(order.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(order.productId())).isEqualTo(5);
    }

    @Test
    @DisplayName("an order the saga finished on its own is never touched by the deadline")
    void aFinishedSagaIsLeftAlone() {
        ProductSnapshot product = catalogue.create(
                new ProductWrite("IT Deadline Normal", "d", new BigDecimal("20.00"), 5, "IT"));
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(product.id(), 1), CartResponse.class);
        long orderId = shopper.postForEntity("/api/orders", null, OrderResponse.class).getBody().id();
        await().atMost(Duration.ofSeconds(30))
                .until(() -> status(orderId).status() == OrderStatus.CONFIRMED);

        sweepAfterTheDeadline();

        assertThat(status(orderId).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'OrderPlacedEvent'",
                        Integer.class, String.valueOf(orderId)))
                .as("announced once, by the saga")
                .isEqualTo(1);
    }
}

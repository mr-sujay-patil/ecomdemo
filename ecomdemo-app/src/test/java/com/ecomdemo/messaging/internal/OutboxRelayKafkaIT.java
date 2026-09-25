package com.ecomdemo.messaging.internal;

import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.messaging.OrderPlacedEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.notification.internal.NotificationRepository;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.clients.catalog.ProductWrite;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checkout to outbox to Kafka to notification, over a real broker and a real database.
 *
 * <p>{@code OrderPlacedKafkaIT} still owns the consumer's half of this journey — retries, the
 * dead-letter topic, idempotency on redelivery. This class owns the producer's half: that the row
 * is written in the order's transaction, that the relay drains it, and that a republished row
 * costs a duplicate send and not a duplicate notification.
 *
 * <p>The broker is UP throughout. Testcontainers shares one Kafka across the whole integration
 * suite, so stopping it here would break every other class that happened to run afterwards — and
 * an outage is exactly the kind of claim that deserves the real thing rather than a mock of it.
 * So the outage lives in {@code scripts/smoke-test.sh}, which stops the actual container in
 * compose, places an order, starts it again and waits for the notification. What this class can
 * do honestly is prove the mechanism that makes the outage survivable: the row exists,
 * independently of Kafka, the moment the order commits.
 */
@DisplayName("Order placed -> outbox -> Kafka")
class OutboxRelayKafkaIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final Duration LONG_ENOUGH_TO_HAVE_HAPPENED = Duration.ofSeconds(3);

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<Long> createdProductIds = new ArrayList<>();

    private TestRestTemplate shopper;
    private TestRestTemplate admin;

    @BeforeEach
    void signIn() {
        admin = asAdmin();
        shopper = asCustomer("it-outbox-shopper");
        emptyTheCart();
    }

    @AfterEach
    void cleanUp() {
        emptyTheCart();
        createdProductIds.forEach(id -> admin.delete("/api/products/" + id));
        createdProductIds.clear();
    }

    private void emptyTheCart() {
        CartResponse cart = shopper.getForObject("/api/cart", CartResponse.class);
        if (cart != null) {
            cart.items().forEach(item -> shopper.delete("/api/cart/items/" + item.productId()));
        }
    }

    private ProductSnapshot createProduct(String name, int stock) {
        ProductSnapshot created =
                admin.postForObject(
                        "/api/products",
                        new ProductWrite(
                                name, name + " description", new BigDecimal("199.00"), stock,
                                "OUTBOX"),
                        ProductSnapshot.class);
        assertThat(created).isNotNull();
        createdProductIds.add(created.id());
        return created;
    }

    private OrderResponse placeAnOrderFor(ProductSnapshot product, int quantity) {
        shopper.postForEntity(
                "/api/cart/items", new AddCartItemRequest(product.id(), quantity), String.class);
        var response = shopper.postForEntity("/api/orders", null, OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private Optional<OutboxEvent> rowFor(long orderId) {
        return outbox.findAll().stream()
                .filter(event -> event.getAggregateId().equals(String.valueOf(orderId)))
                .findFirst();
    }

    @Test
    @DisplayName("a placed order leaves a row in the outbox, and the relay publishes it")
    void theRelayDrainsTheOutbox() {
        ProductSnapshot product = createProduct("Outbox Lamp", 5);

        OrderResponse order = placeAnOrderFor(product, 2);

        // The row exists because the order committed — not because Kafka was reachable. That
        // independence is the entire guarantee, and it is true at this exact line whether the
        // broker is up, down, or on fire.
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(rowFor(order.id())).isPresent());

        await().atMost(TIMEOUT)
                .untilAsserted(
                        () -> {
                            OutboxEvent row = rowFor(order.id()).orElseThrow();
                            assertThat(row.isPublished())
                                    .as("the relay marks the row published once the broker acks")
                                    .isTrue();
                            assertThat(row.getAttempts())
                                    .as("a healthy broker needs no second attempt")
                                    .isZero();
                            assertThat(row.getLastError()).isNull();
                        });

        await().atMost(TIMEOUT)
                .untilAsserted(
                        () ->
                                assertThat(notifications.countByOrderId(order.id()))
                                        .as("exactly one notification for order %d", order.id())
                                        .isEqualTo(1));
    }

    @Test
    @DisplayName("the row carries the order's own event id, and the payload the consumer reads")
    void theRowIsTheMessage() {
        ProductSnapshot product = createProduct("Outbox Payload Lamp", 5);

        OrderResponse order = placeAnOrderFor(product, 1);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(rowFor(order.id())).isPresent());
        OutboxEvent row = rowFor(order.id()).orElseThrow();

        assertThat(row.getAggregateType()).isEqualTo("Order");
        assertThat(row.getEventType()).isEqualTo("OrderPlacedEvent");
        assertThat(row.getEventId()).isNotNull();
        assertThat(row.getPayload())
                .contains("\"orderId\":" + order.id())
                .contains("it-outbox-shopper");
    }

    /**
     * THE PHASE'S OTHER HALF, and the one people forget.
     *
     * <p>An event announcing an order that does not exist is as wrong as an order with no event.
     * Because the row is written in the order's transaction, a rejected checkout rolls it back
     * along with everything else — there is nothing to un-send, because nothing was ever
     * recorded.
     */
    @Test
    @DisplayName("a rejected checkout leaves NO outbox row, because the transaction rolled back")
    void aRejectedCheckoutWritesNothing() {
        ProductSnapshot product = createProduct("Outbox Scarce Lamp", 1);
        long rowsBefore = outbox.count();

        shopper.postForEntity(
                "/api/cart/items", new AddCartItemRequest(product.id(), 5), String.class);
        var refused = shopper.postForEntity("/api/orders", null, String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        await().during(LONG_ENOUGH_TO_HAVE_HAPPENED)
                .atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(outbox.count()).isEqualTo(rowsBefore));
    }

    /**
     * The at-least-once seam, made to happen on purpose.
     *
     * <p>A relay that publishes a row and dies before committing the mark will publish it again.
     * Rather than kill a process to prove it, this un-marks a published row, which is the same
     * state the crash would have left behind — and then asserts the thing that makes it safe:
     * the republished message carries the same event id, so {@code processed_event} refuses it
     * and no second notification is written.
     *
     * <p>This is why Phase 17 had to build the idempotent consumer before Phase 18 could build
     * the outbox. The outbox CREATES duplicates; it is only an improvement because something
     * downstream already absorbs them.
     */
    @Test
    @DisplayName("a republished row costs a duplicate SEND, never a duplicate notification")
    void republicationIsAbsorbedByTheConsumer() {
        ProductSnapshot product = createProduct("Outbox Replay Lamp", 5);
        OrderResponse order = placeAnOrderFor(product, 1);

        await().atMost(TIMEOUT)
                .untilAsserted(
                        () -> {
                            assertThat(rowFor(order.id()).map(OutboxEvent::isPublished))
                                    .contains(true);
                            assertThat(notifications.countByOrderId(order.id())).isEqualTo(1);
                        });

        // Put the row back into the state a crash between the send and the commit would leave.
        long rowId = rowFor(order.id()).orElseThrow().getId();
        transactionTemplate.executeWithoutResult(
                status ->
                        outbox.findById(rowId)
                                .ifPresent(
                                        row ->
                                                outbox.save(
                                                        unpublish(row))));

        await().during(LONG_ENOUGH_TO_HAVE_HAPPENED)
                .atMost(TIMEOUT)
                .untilAsserted(
                        () -> {
                            assertThat(rowFor(order.id()).map(OutboxEvent::isPublished))
                                    .as("the relay picks the row up again and republishes it")
                                    .contains(true);
                            assertThat(notifications.countByOrderId(order.id()))
                                    .as("still exactly one notification, absorbed by processed_event")
                                    .isEqualTo(1);
                        });
    }

    /**
     * Reflection, deliberately, and only in the test.
     *
     * <p>{@code OutboxEvent} has no way to un-publish a row because no production code path should
     * ever be able to. Re-opening that door with a package-private setter "for testing" would put
     * it within reach of the relay, which is precisely where it must not be. A test that needs an
     * impossible state can reach for reflection; production code cannot.
     */
    private static OutboxEvent unpublish(OutboxEvent row) {
        try {
            var field = OutboxEvent.class.getDeclaredField("publishedAt");
            field.setAccessible(true);
            field.set(row, null);
            return row;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not un-publish the row for the test", e);
        }
    }

    /**
     * That {@code Propagation.MANDATORY} is real, and not a comment.
     *
     * <p>The outbox is worth nothing if the append can run in a transaction of its own — that
     * would be the dual-write problem again, one layer down, behind code that looks like a working
     * outbox. {@code MANDATORY} turns "must be called inside a transaction" from a convention into
     * a runtime refusal, and this is the test that the refusal actually happens.
     *
     * <p>It is an integration test because the enforcement lives in Spring's proxy, not in the
     * method: calling {@code new OutboxWriter(...).append(...)} directly would bypass exactly the
     * mechanism under test and pass regardless.
     */
    @Test
    @DisplayName("appending outside a transaction is REFUSED, not quietly given one")
    void theAppendCannotEscapeItsTransaction() {
        OrderPlacedEvent event =
                OrderPlacedEvent.of(1L, "it-outbox-shopper", new BigDecimal("1.00"), 1, Instant.now());

        assertThatThrownBy(() -> outboxWriter.append(event))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found");

        // And inside one, it works — so the refusal above is about the transaction and not about
        // the event.
        long before = outbox.count();
        transactionTemplate.executeWithoutResult(status -> outboxWriter.append(event));
        assertThat(outbox.count()).isEqualTo(before + 1);
    }
}

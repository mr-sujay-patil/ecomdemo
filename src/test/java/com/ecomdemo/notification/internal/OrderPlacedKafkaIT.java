package com.ecomdemo.notification.internal;

import com.ecomdemo.notification.Notification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.internal.ProcessedEventRepository;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Checkout to notification, over a real broker in a container.
 *
 * <p>Every assertion here is of the form "eventually", because that is what asynchronous means:
 * the HTTP response returns before the consumer has run, and a test that asserted immediately
 * would be asserting that the design had failed. {@code Awaitility} polls rather than sleeping —
 * a fixed {@code Thread.sleep(2000)} is both slower than it needs to be on a good day and flaky
 * on a bad one.
 *
 * <p>The two negative tests wait {@code during} a window as well as {@code atMost} one. "No
 * second notification appeared" is a claim about something NOT happening, and it is trivially
 * true one millisecond after the send; it only means something if the window is long enough for
 * the thing to have happened — which the first test establishes by showing a real notification
 * arriving well inside it.
 */
@DisplayName("Order placed -> Kafka -> notification")
class OrderPlacedKafkaIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final Duration LONG_ENOUGH_TO_HAVE_HAPPENED = Duration.ofSeconds(3);

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final List<Long> createdProductIds = new ArrayList<>();

    private TestRestTemplate shopper;
    private TestRestTemplate admin;

    @BeforeEach
    void signIn() {
        admin = asAdmin();
        shopper = asCustomer("it-kafka-shopper");
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

    private ProductResponse createProduct(String name, int stock) {
        ProductResponse created =
                admin.postForObject(
                        "/api/products",
                        new ProductRequest(
                                name, name + " description", new BigDecimal("199.00"), stock, "KAFKA"),
                        ProductResponse.class);
        assertThat(created).isNotNull();
        createdProductIds.add(created.id());
        return created;
    }

    private OrderResponse placeAnOrderFor(ProductResponse product, int quantity) {
        shopper.postForEntity(
                "/api/cart/items", new AddCartItemRequest(product.id(), quantity), String.class);
        var response = shopper.postForEntity("/api/orders", null, OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @Test
    @DisplayName("a placed order produces exactly one notification")
    void aPlacedOrderIsNotified() {
        ProductResponse product = createProduct("Kafka Notified Lamp", 5);

        OrderResponse order = placeAnOrderFor(product, 2);

        await().atMost(TIMEOUT)
                .untilAsserted(
                        () ->
                                assertThat(notifications.countByOrderId(order.id()))
                                        .as("one notification for order %d", order.id())
                                        .isEqualTo(1));

        Notification notification = notifications.findByOrderId(order.id()).getFirst();
        assertThat(notification.getRecipient()).isEqualTo("it-kafka-shopper");
        assertThat(notification.getMessage()).contains(String.valueOf(order.id()));
    }

    @Test
    @DisplayName("a REDELIVERED event writes no second notification — the consumer is idempotent")
    void redeliveryIsIdempotent() {
        // Kafka delivers at least once, and the honest way to test a consumer's idempotency is to
        // deliver the same event twice on purpose rather than to hope a rebalance does it. Both
        // sends carry the SAME event id, because that is what a real redelivery is: the same
        // bytes, handed over again.
        UUID eventId = UUID.randomUUID();
        long orderId = 987_654L;
        OrderPlacedEvent event =
                new OrderPlacedEvent(
                        eventId,
                        orderId,
                        "it-kafka-shopper",
                        new BigDecimal("42.00"),
                        1,
                        Instant.now());

        kafkaTemplate.send(KafkaTopics.ORDERS_PLACED, String.valueOf(orderId), event);
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(notifications.countByOrderId(orderId)).isEqualTo(1));

        kafkaTemplate.send(KafkaTopics.ORDERS_PLACED, String.valueOf(orderId), event);

        await().during(LONG_ENOUGH_TO_HAVE_HAPPENED)
                .atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(notifications.countByOrderId(orderId)).isEqualTo(1));

        assertThat(processedEvents.existsById(eventId))
                .as("the event id is remembered, which is what makes the second delivery a no-op")
                .isTrue();
    }

    @Test
    @DisplayName("a rejected checkout publishes nothing, because the transaction never committed")
    void aRejectedCheckoutPublishesNothing() {
        ProductResponse product = createProduct("Kafka Scarce Lamp", 1);
        long notificationsBefore = notifications.count();

        // Ask for more than exists: the cart accepts it, checkout refuses with 409 and rolls back.
        shopper.postForEntity(
                "/api/cart/items", new AddCartItemRequest(product.id(), 5), String.class);
        var refused = shopper.postForEntity("/api/orders", null, String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        await().during(LONG_ENOUGH_TO_HAVE_HAPPENED)
                .atMost(TIMEOUT)
                .untilAsserted(
                        () ->
                                assertThat(notifications.count())
                                        .as("an order that never existed must not be announced")
                                        .isEqualTo(notificationsBefore));
    }

    @Test
    @DisplayName("a poison message does not block the partition behind it")
    void aPoisonMessageDoesNotBlockThePartition() {
        // Bytes that are not the expected JSON at all. This fails in the DESERIALIZER, before any
        // application code runs — the case that would otherwise be unrecoverable: nothing can
        // catch it, the offset is never committed, and the consumer re-reads the same bad bytes
        // for ever. ErrorHandlingDeserializer is what turns it into a routable failure, and
        // @RetryableTopic is what moves it off the partition.
        kafkaTemplate.send(
                KafkaTopics.ORDERS_PLACED, "poison", "{\"this\":\"is not an OrderPlacedEvent\"}");

        // The proof is that the partition KEEPS MOVING. A good order placed after the poison
        // message is still notified — which is precisely what would not happen if the bad record
        // were being retried in place for ever, and it is a stronger statement than reading the
        // DLT, because it is the behaviour anyone would actually notice.
        ProductResponse product = createProduct("Kafka After Poison", 3);
        OrderResponse order = placeAnOrderFor(product, 1);

        await().atMost(TIMEOUT)
                .untilAsserted(
                        () ->
                                assertThat(notifications.countByOrderId(order.id()))
                                        .as("the consumer is not stuck on the poison message")
                                        .isEqualTo(1));
    }
}

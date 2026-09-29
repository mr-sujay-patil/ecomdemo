package com.ecomdemo.inventory.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.inventory.InventoryService;
import com.ecomdemo.outbox.internal.OutboxEvent;
import com.ecomdemo.outbox.internal.OutboxEventRepository;
import com.ecomdemo.outbox.internal.ProcessedEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * inventory-service's two saga steps, against a real PostgreSQL with the real Flyway schema.
 *
 * <p>PostgreSQL rather than the fast suite's H2 for two reasons: V3 is what creates the outbox,
 * processed_event and stock_reservation tables, so running it IS part of the test; and the
 * reservation locks rows with {@code SELECT ... FOR UPDATE}, whose behaviour under concurrency is
 * the database's, not Hibernate's.
 *
 * <p>The handler is called directly rather than through Kafka. What arrives over Kafka is a
 * record the listener hands straight to the handler; everything that can go wrong - the claim,
 * the stock, the outbox row - happens in the handler's transaction, which is what is exercised
 * here. The Kafka hop itself is covered end to end by the smoke test's "Saga" section.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    // The relay would try to publish every row written here to a broker that is not there.
    // An hour means it never ticks, so each test sees the rows exactly as the handler left them.
    "ecomdemo.outbox.poll-delay=1h"
})
@Import(InventorySagaTest.Containers.class)
@DisplayName("Inventory saga steps")
class InventorySagaTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"));
        }
    }

    /** stock-changed is still sent straight after commit; there is no broker to send it to. */
    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private InventorySagaHandler handler;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private JdbcTemplate jdbc;

    /** Every test uses its own order and product ids, so the shared database needs no cleaning. */
    private static final AtomicLong IDS = new AtomicLong(700_000);

    private long orderId;
    private long mouse;
    private long keyboard;

    @BeforeEach
    void freshIds() {
        orderId = IDS.incrementAndGet();
        mouse = IDS.incrementAndGet();
        keyboard = IDS.incrementAndGet();
        inventory.setStockLevel(mouse, 5);
        inventory.setStockLevel(keyboard, 2);
    }

    private OrderCreatedEvent order(long id, OrderCreatedEvent.Line... lines) {
        return new OrderCreatedEvent(
                UUID.randomUUID(), id, "shopper", new BigDecimal("149.97"), List.of(lines), Instant.now());
    }

    private OrderCreatedEvent.Line line(long productId, int quantity) {
        return new OrderCreatedEvent.Line(productId, "Product " + productId, quantity);
    }

    private List<OutboxEvent> outboxRowsFor(long order) {
        return outbox.findAll().stream()
                .filter(row -> row.getAggregateId().equals(String.valueOf(order)))
                .toList();
    }

    private List<String> reservationStatuses(long order) {
        return jdbc.queryForList(
                "SELECT status FROM stock_reservation WHERE order_id = ? ORDER BY product_id",
                String.class,
                order);
    }

    private static <T> T read(OutboxEvent row, Class<T> type) throws Exception {
        // The consumer's own mapper, as in the outbox library's tests.
        return JacksonUtils.enhancedObjectMapper().readValue(row.getPayload(), type);
    }

    @Nested
    @DisplayName("OrderCreated")
    class OrderCreated {

        @Test
        @DisplayName("reserves every line, remembers it, and announces StockReserved")
        void reservesEverything() throws Exception {
            OrderCreatedEvent event = order(orderId, line(mouse, 3), line(keyboard, 2));

            handler.onOrderCreated(event);

            assertThat(inventory.quantityFor(mouse)).isEqualTo(2);
            assertThat(inventory.quantityFor(keyboard)).isZero();
            assertThat(reservationStatuses(orderId)).containsExactly("RESERVED", "RESERVED");

            List<OutboxEvent> rows = outboxRowsFor(orderId);
            assertThat(rows).singleElement()
                    .satisfies(row -> assertThat(row.getEventType()).isEqualTo("StockReservedEvent"));
            StockReservedEvent reserved = read(rows.get(0), StockReservedEvent.class);
            // Passed through for payment-service, which has no other way to learn the amount.
            assertThat(reserved.totalAmount()).isEqualByComparingTo("149.97");
            assertThat(reserved.username()).isEqualTo("shopper");
            assertThat(reserved.eventId()).isNotEqualTo(event.eventId());
        }

        @Test
        @DisplayName("takes nothing when ANY line is short, and announces StockRejected with the reason")
        void rejectsAllOrNothing() throws Exception {
            // The mouse line alone would fit; the keyboard line does not. All or nothing.
            handler.onOrderCreated(order(orderId, line(mouse, 1), line(keyboard, 3)));

            assertThat(inventory.quantityFor(mouse)).as("not even the line that fitted").isEqualTo(5);
            assertThat(inventory.quantityFor(keyboard)).isEqualTo(2);
            assertThat(reservationStatuses(orderId)).isEmpty();

            List<OutboxEvent> rows = outboxRowsFor(orderId);
            assertThat(rows).singleElement()
                    .satisfies(row -> assertThat(row.getEventType()).isEqualTo("StockRejectedEvent"));
            assertThat(read(rows.get(0), StockRejectedEvent.class).reason())
                    .isEqualTo("Insufficient stock for 'Product %d': requested 3, available 2", keyboard);
        }

        @Test
        @DisplayName("treats a product with no stock row as having none")
        void noRowMeansNone() throws Exception {
            long neverStocked = IDS.incrementAndGet();

            handler.onOrderCreated(order(orderId, line(neverStocked, 1)));

            assertThat(read(outboxRowsFor(orderId).get(0), StockRejectedEvent.class).reason())
                    .contains("requested 1, available 0");
        }

        @Test
        @DisplayName("adds duplicate lines of one product together before checking")
        void sumsDuplicateLines() {
            // 3 + 3 of a product with 5: each line alone fits, together they do not.
            handler.onOrderCreated(order(orderId, line(mouse, 3), line(mouse, 3)));

            assertThat(inventory.quantityFor(mouse)).isEqualTo(5);
            assertThat(outboxRowsFor(orderId).get(0).getEventType()).isEqualTo("StockRejectedEvent");
        }

        @Test
        @DisplayName("a redelivered event reserves nothing more and announces nothing more")
        void redeliveryIsIgnored() {
            OrderCreatedEvent event = order(orderId, line(mouse, 2));

            handler.onOrderCreated(event);
            handler.onOrderCreated(event);

            assertThat(inventory.quantityFor(mouse)).isEqualTo(3);
            assertThat(reservationStatuses(orderId)).containsExactly("RESERVED");
            assertThat(outboxRowsFor(orderId)).hasSize(1);
            assertThat(processedEvents.existsById(event.eventId())).isTrue();
        }

        @Test
        @DisplayName("two orders racing for the last unit: exactly one is reserved")
        void lastUnitRace() throws Exception {
            long lastOne = IDS.incrementAndGet();
            inventory.setStockLevel(lastOne, 1);
            long first = IDS.incrementAndGet();
            long second = IDS.incrementAndGet();

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            for (long order : List.of(first, second)) {
                threads.submit(() -> {
                    start.await();
                    handler.onOrderCreated(order(order, line(lastOne, 1)));
                    return null;
                });
            }
            start.countDown();
            threads.shutdown();
            assertThat(threads.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            // The row lock makes the second wait, then see zero and reject - rather than both
            // passing the check, which is what an unlocked read-then-write would allow.
            List<String> outcomes = List.of(
                    outboxRowsFor(first).get(0).getEventType(),
                    outboxRowsFor(second).get(0).getEventType());
            assertThat(outcomes).containsExactlyInAnyOrder("StockReservedEvent", "StockRejectedEvent");
            assertThat(inventory.quantityFor(lastOne)).isZero();
        }
    }

    @Nested
    @DisplayName("PaymentFailed (the compensation)")
    class PaymentFailed {

        private PaymentFailedEvent failed(long order) {
            return new PaymentFailedEvent(
                    UUID.randomUUID(), order, new BigDecimal("149.97"), "Declined", Instant.now());
        }

        @Test
        @DisplayName("gives back exactly what the order held and marks it released")
        void releasesTheReservation() {
            handler.onOrderCreated(order(orderId, line(mouse, 3), line(keyboard, 2)));

            handler.onPaymentFailed(failed(orderId));

            assertThat(inventory.quantityFor(mouse)).isEqualTo(5);
            assertThat(inventory.quantityFor(keyboard)).isEqualTo(2);
            assertThat(reservationStatuses(orderId)).containsExactly("RELEASED", "RELEASED");
            // No event follows a compensation: the order service cancels on PaymentFailed itself.
            assertThat(outboxRowsFor(orderId)).hasSize(1);
        }

        @Test
        @DisplayName("never releases twice, even for a DIFFERENT event about the same order")
        void releasesOnlyOnce() {
            handler.onOrderCreated(order(orderId, line(mouse, 3)));

            PaymentFailedEvent event = failed(orderId);
            handler.onPaymentFailed(event);
            handler.onPaymentFailed(event);              // a redelivery: stopped by processed_event
            handler.onPaymentFailed(failed(orderId));    // a new event id: stopped by the status

            assertThat(inventory.quantityFor(mouse)).as("5, never 8 or 11").isEqualTo(5);
        }

        @Test
        @DisplayName("does nothing for an order that holds nothing")
        void nothingHeld() {
            handler.onPaymentFailed(failed(orderId));

            assertThat(inventory.quantityFor(mouse)).isEqualTo(5);
            assertThat(reservationStatuses(orderId)).isEmpty();
        }
    }

    /** Phase 32: the saga deadline gives an order's stock back and fences it. */
    @Nested
    @DisplayName("closing an order for the saga deadline")
    class ClosingAnOrder {

        @Test
        @DisplayName("gives back everything the order holds")
        void releasesWhatIsHeld() {
            handler.onOrderCreated(order(orderId, line(mouse, 3), line(keyboard, 1)));

            assertThat(inventory.closeOrder(orderId, "deadline").released()).isEqualTo(2);

            assertThat(inventory.quantityFor(mouse)).isEqualTo(5);
            assertThat(inventory.quantityFor(keyboard)).isEqualTo(2);
            assertThat(reservationStatuses(orderId)).containsExactly("RELEASED", "RELEASED");
        }

        @Test
        @DisplayName("is idempotent: a second close releases nothing and says so")
        void closingTwice() {
            handler.onOrderCreated(order(orderId, line(mouse, 3)));

            inventory.closeOrder(orderId, "deadline");
            var second = inventory.closeOrder(orderId, "deadline");

            assertThat(second.released()).isZero();
            assertThat(second.alreadyClosed()).isTrue();
            assertThat(inventory.quantityFor(mouse)).as("5, never 8").isEqualTo(5);
        }

        @Test
        @DisplayName("THE FENCE: an OrderCreated arriving after the close reserves nothing")
        void aLateOrderCreatedIsRejected() throws Exception {
            inventory.closeOrder(orderId, "deadline");   // its OrderCreated was dead-lettered

            handler.onOrderCreated(order(orderId, line(mouse, 3)));   // ...and is replayed

            assertThat(inventory.quantityFor(mouse)).isEqualTo(5);
            assertThat(reservationStatuses(orderId)).isEmpty();
            OutboxEvent row = outboxRowsFor(orderId).get(0);
            assertThat(row.getEventType()).isEqualTo("StockRejectedEvent");
            assertThat(read(row, StockRejectedEvent.class).reason())
                    .isEqualTo("The order was closed before its stock could be reserved");
        }

        @Test
        @DisplayName("never leaves stock held for a closed order, however a close and a reservation interleave")
        void closeRacingAReservation() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 20; round++) {
                    long racedOrder = IDS.incrementAndGet();
                    CountDownLatch start = new CountDownLatch(1);
                    var reserve = pool.submit(() -> {
                        start.await();
                        handler.onOrderCreated(order(racedOrder, line(mouse, 1)));
                        return null;
                    });
                    var close = pool.submit(() -> {
                        start.await();
                        inventory.closeOrder(racedOrder, "deadline");
                        return null;
                    });
                    start.countDown();
                    reserve.get(30, TimeUnit.SECONDS);
                    close.get(30, TimeUnit.SECONDS);

                    assertThat(reservationStatuses(racedOrder))
                            .as("round %d: nothing may stay RESERVED for a closed order", round)
                            .doesNotContain("RESERVED");
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(inventory.quantityFor(mouse)).as("every unit came back").isEqualTo(5);
        }
    }
}

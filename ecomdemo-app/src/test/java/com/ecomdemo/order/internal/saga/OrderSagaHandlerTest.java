package com.ecomdemo.order.internal.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.order.Order;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.internal.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order service's end of the saga: the three replies, against the real schema (Flyway on H2,
 * the test profile) so the conditional UPDATE and the {@code processed_event} key are the
 * database's, not a mock's.
 *
 * <p>The handler is called directly; the Kafka hop is the smoke test's to prove.
 */
@SpringBootTest(properties = "ecomdemo.outbox.poll-delay=1h")
@DisplayName("Order saga replies")
class OrderSagaHandlerTest {

    @Autowired
    private OrderSagaHandler handler;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private Long orderId;

    @BeforeEach
    void aPendingOrder() {
        orderId = transactions.execute(status -> {
            Order order = new Order(Instant.now(), 4242L, "saga-shopper");
            order.addItem(10L, "Lamp", new BigDecimal("1500.00"), 2);
            order.addItem(11L, "Cable", new BigDecimal("100.50"), 1);
            return orders.save(order).getId();
        });
    }

    private Order reload() {
        return transactions.execute(status -> orders.findByIdWithItems(orderId).orElseThrow());
    }

    private List<String> outboxEventTypes() {
        return jdbc.queryForList(
                "SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY id",
                String.class,
                String.valueOf(orderId));
    }

    private PaymentCompletedEvent completed() {
        return new PaymentCompletedEvent(
                UUID.randomUUID(), orderId, 77L, new BigDecimal("3100.50"), Instant.now());
    }

    private PaymentFailedEvent failed() {
        return new PaymentFailedEvent(
                UUID.randomUUID(), orderId, new BigDecimal("3100.50"),
                "Payment declined: 3100.50 exceeds the limit of 100.00", Instant.now());
    }

    @Test
    @DisplayName("PaymentCompleted confirms the order and only then announces OrderPlaced")
    void paymentCompletedConfirms() {
        handler.onPaymentCompleted(completed());

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getStatusReason()).isNull();
        assertThat(order.getStatusChangedAt()).isNotNull();
        // What notification-service thanks the shopper for - published on confirmation now.
        assertThat(outboxEventTypes()).containsExactly("OrderPlacedEvent");
        assertThat(jdbc.queryForObject(
                        "SELECT payload FROM outbox_event WHERE aggregate_id = ?",
                        String.class, String.valueOf(orderId)))
                .contains("\"username\":\"saga-shopper\"")
                .contains("\"itemCount\":2");
    }

    @Test
    @DisplayName("PaymentFailed cancels the order with the reason, and thanks nobody")
    void paymentFailedCancels() {
        handler.onPaymentFailed(failed());

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusReason())
                .isEqualTo("Payment declined: 3100.50 exceeds the limit of 100.00");
        assertThat(outboxEventTypes()).isEmpty();
    }

    @Test
    @DisplayName("StockRejected cancels the order with the reason")
    void stockRejectedCancels() {
        handler.onStockRejected(new StockRejectedEvent(
                UUID.randomUUID(), orderId,
                "Insufficient stock for 'Lamp': requested 2, available 1", Instant.now()));

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatusReason()).contains("requested 2, available 1");
        assertThat(outboxEventTypes()).isEmpty();
    }

    @Test
    @DisplayName("a redelivered PaymentCompleted announces the order once")
    void redeliveredConfirmation() {
        PaymentCompletedEvent event = completed();

        handler.onPaymentCompleted(event);
        handler.onPaymentCompleted(event);

        assertThat(outboxEventTypes()).containsExactly("OrderPlacedEvent");
    }

    @Test
    @DisplayName("a reply for an order that is already decided changes nothing")
    void lateReplyIsIgnored() {
        handler.onPaymentCompleted(completed());

        // A different event, so processed_event does not stop it - the status does.
        handler.onPaymentFailed(failed());
        handler.onPaymentCompleted(completed());

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getStatusReason()).isNull();
        assertThat(outboxEventTypes()).containsExactly("OrderPlacedEvent");
    }
}

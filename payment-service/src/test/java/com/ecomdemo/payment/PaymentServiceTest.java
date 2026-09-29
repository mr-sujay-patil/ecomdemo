package com.ecomdemo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.outbox.internal.OutboxEvent;
import com.ecomdemo.outbox.internal.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.support.JacksonUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * payment-service's saga step against a real PostgreSQL, so V1's constraints are part of the
 * test. The listener is bypassed for the reason given in inventory's {@code InventorySagaTest}.
 *
 * <p>The limit is set to 100.00 here, so the tests read naturally in small numbers.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "ecomdemo.outbox.poll-delay=1h",
    "ecomdemo.payment.decline-above=100.00"
})
@Import(PaymentServiceTest.Containers.class)
@DisplayName("Payment saga step")
class PaymentServiceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"));
        }
    }

    private static final AtomicLong ORDER_IDS = new AtomicLong(900_000);

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private OutboxEventRepository outbox;

    private static StockReservedEvent reserved(long orderId, String amount) {
        return new StockReservedEvent(
                UUID.randomUUID(), orderId, "shopper", new BigDecimal(amount), Instant.now());
    }

    private List<OutboxEvent> outboxRowsFor(long orderId) {
        return outbox.findAll().stream()
                .filter(row -> row.getAggregateId().equals(String.valueOf(orderId)))
                .toList();
    }

    @Test
    @DisplayName("charges an order within the limit and announces PaymentCompleted")
    void completes() throws Exception {
        long orderId = ORDER_IDS.incrementAndGet();

        payments.onStockReserved(reserved(orderId, "59.97"));

        Payment payment = repository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.COMPLETED);
        assertThat(payment.getAmount()).isEqualByComparingTo("59.97");
        assertThat(payment.getReason()).isNull();

        OutboxEvent row = outboxRowsFor(orderId).get(0);
        assertThat(row.getEventType()).isEqualTo("PaymentCompletedEvent");
        PaymentCompletedEvent event = JacksonUtils.enhancedObjectMapper()
                .readValue(row.getPayload(), PaymentCompletedEvent.class);
        assertThat(event.paymentId()).isEqualTo(payment.getId());
    }

    @Test
    @DisplayName("declines an order above the limit, keeps the decline, and announces PaymentFailed")
    void declines() throws Exception {
        long orderId = ORDER_IDS.incrementAndGet();

        payments.onStockReserved(reserved(orderId, "100.01"));

        Payment payment = repository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(payment.getReason())
                .isEqualTo("Payment declined: 100.01 exceeds the limit of 100.00");

        OutboxEvent row = outboxRowsFor(orderId).get(0);
        assertThat(row.getEventType()).isEqualTo("PaymentFailedEvent");
        assertThat(JacksonUtils.enhancedObjectMapper()
                        .readValue(row.getPayload(), PaymentFailedEvent.class).reason())
                .isEqualTo(payment.getReason());
    }

    @Test
    @DisplayName("accepts a total exactly AT the limit, as a card limit would")
    void limitIsInclusive() {
        long orderId = ORDER_IDS.incrementAndGet();

        payments.onStockReserved(reserved(orderId, "100.00"));

        assertThat(repository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.COMPLETED);
    }

    @Test
    @DisplayName("a redelivered event charges nothing more and announces nothing more")
    void redelivery() {
        long orderId = ORDER_IDS.incrementAndGet();
        StockReservedEvent event = reserved(orderId, "10.00");

        payments.onStockReserved(event);
        payments.onStockReserved(event);

        assertThat(outboxRowsFor(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("never charges one order twice, even from a DIFFERENT event")
    void oneChargePerOrder() {
        long orderId = ORDER_IDS.incrementAndGet();

        payments.onStockReserved(reserved(orderId, "10.00"));
        payments.onStockReserved(reserved(orderId, "10.00"));

        assertThat(repository.findAll().stream().filter(p -> p.getOrderId() == orderId)).hasSize(1);
        assertThat(outboxRowsFor(orderId)).hasSize(1);
    }

    // --- Phase 32: settling an order for the saga deadline -----------------------------------------

    @Test
    @DisplayName("settling an order nobody paid for VOIDS it and announces PaymentFailed")
    void settleVoidsAnUnpaidOrder() throws Exception {
        long orderId = ORDER_IDS.incrementAndGet();

        Payment settled = payments.settle(orderId, new BigDecimal("42.00"));

        assertThat(settled.getStatus()).isEqualTo(Payment.Status.VOIDED);
        assertThat(settled.getReason()).isEqualTo(PaymentService.VOID_REASON);
        OutboxEvent row = outboxRowsFor(orderId).get(0);
        assertThat(row.getEventType()).isEqualTo("PaymentFailedEvent");
        assertThat(JacksonUtils.enhancedObjectMapper()
                        .readValue(row.getPayload(), PaymentFailedEvent.class).reason())
                .isEqualTo(PaymentService.VOID_REASON);
    }

    @Test
    @DisplayName("settling an order that WAS paid reports the payment and changes nothing")
    void settleReportsAnExistingPayment() {
        long orderId = ORDER_IDS.incrementAndGet();
        payments.onStockReserved(reserved(orderId, "10.00"));

        Payment settled = payments.settle(orderId, new BigDecimal("10.00"));

        assertThat(settled.getStatus()).isEqualTo(Payment.Status.COMPLETED);
        assertThat(outboxRowsFor(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("settling twice gives the same answer and voids once")
    void settleIsIdempotent() {
        long orderId = ORDER_IDS.incrementAndGet();

        Payment first = payments.settle(orderId, new BigDecimal("5.00"));
        Payment second = payments.settle(orderId, new BigDecimal("5.00"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(outboxRowsFor(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("THE FENCE: a StockReserved arriving after the void charges nothing")
    void aLateStockReservedCannotChargeAVoidedOrder() {
        long orderId = ORDER_IDS.incrementAndGet();
        payments.settle(orderId, new BigDecimal("10.00"));

        payments.onStockReserved(reserved(orderId, "10.00"));

        assertThat(repository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.VOIDED);
        assertThat(outboxRowsFor(orderId)).hasSize(1);
    }
}

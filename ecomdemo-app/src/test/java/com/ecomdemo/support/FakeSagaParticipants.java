package com.ecomdemo.support;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.messaging.OrderCreatedEvent;
import com.ecomdemo.order.internal.saga.PaymentCompletedEvent;
import com.ecomdemo.order.internal.saga.PaymentFailedEvent;
import com.ecomdemo.order.internal.saga.SagaParticipants;
import com.ecomdemo.order.internal.saga.StockRejectedEvent;
import com.ecomdemo.shared.InsufficientStockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * inventory-service and payment-service, played by one fake, over the REAL Kafka container
 * (Phase 24).
 *
 * <p>The integration suite runs this application alone. Before the saga that was enough: checkout
 * reserved stock through {@code InventoryGateway}, which the suite replaced with
 * {@link InMemoryInventory}. Now checkout only publishes {@code OrderCreated}, and without
 * somebody to answer it every order here would stay PENDING for ever.
 *
 * <p>So this answers it - through Kafka, not through a method call. The application's outbox and
 * relay publish {@code orders.created} to the container; this listener reads it, reserves in the
 * same in-memory inventory the rest of the suite uses, and publishes the reply the real services
 * would; the application's REAL saga listeners consume that reply and confirm or cancel the order.
 * Every part of the order service's side is real. Only the other two services are stand-ins, and
 * they are tested for real in their own modules and together in the smoke test.
 *
 * <p>It collapses inventory's and payment's steps into one - there is no
 * {@code inventory.stock-reserved} hop in between - because that hop is invisible to the order
 * service, which is what these tests are about.
 *
 * <h2>Phase 32: losing a message on purpose, and answering the saga deadline</h2>
 *
 * <p>A test can make one of the saga's messages go missing for a product, as if it had been
 * dead-lettered: {@link #LOSE_ORDER_CREATED} (inventory never sees the order),
 * {@link #LOSE_STOCK_RESERVED} (stock is held but payment never runs) and
 * {@link #LOSE_PAYMENT_REPLY} (the customer is charged but the order service is never told).
 *
 * <p>The saga deadline then asks the participants what happened, through {@link SagaParticipants}.
 * {@link Settlement} answers from the same state this fake keeps while playing the saga, with the
 * real services' rules: settling an unpaid order VOIDS it, closing an order gives its stock back and
 * FENCES it against a late reservation.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeSagaParticipants {

    /** The same rule as the real payment-service's default: decline totals above 10,000.00. */
    public static final BigDecimal DECLINE_ABOVE = new BigDecimal("10000.00");

    private static final AtomicLong PAYMENT_IDS = new AtomicLong(1);

    /**
     * The reply topics, which in compose are declared by the services that publish them. Nothing
     * here publishes them but this fake, so it declares them.
     */
    @Bean
    NewTopic itStockRejectedTopic() {
        return TopicBuilder.name(KafkaTopics.STOCK_REJECTED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic itPaymentsCompletedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENTS_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic itPaymentsFailedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENTS_FAILED).partitions(3).replicas(1).build();
    }

    /** Products whose OrderCreated is lost: inventory never reserves, nobody replies. */
    public static final Set<Long> LOSE_ORDER_CREATED = ConcurrentHashMap.newKeySet();

    /** Products whose StockReserved is lost: stock is HELD, but payment never runs. */
    public static final Set<Long> LOSE_STOCK_RESERVED = ConcurrentHashMap.newKeySet();

    /** Products whose payment reply is lost: the customer is CHARGED, the order service never hears. */
    public static final Set<Long> LOSE_PAYMENT_REPLY = ConcurrentHashMap.newKeySet();

    /** When true, payment-service "does not answer": every settlement is an unknown outcome. */
    public static final AtomicBoolean PAYMENT_UNREACHABLE = new AtomicBoolean();

    /** Every order this fake has read an OrderCreated for, lost or not. */
    public static final Set<Long> SEEN = ConcurrentHashMap.newKeySet();

    /** The fake payment-service's table: order id → its one payment. */
    static final Map<Long, SagaParticipants.PaymentVerdict> PAYMENTS = new ConcurrentHashMap<>();

    /** The fake inventory-service's reservations and fence. */
    static final Map<Long, List<OrderCreatedEvent.Line>> RESERVED = new ConcurrentHashMap<>();
    static final Set<Long> CLOSED = ConcurrentHashMap.newKeySet();

    @Bean
    Participants fakeSagaParticipants(InMemoryInventory inventory, KafkaTemplate<String, Object> kafka) {
        return new Participants(inventory, kafka);
    }

    @Bean
    @Primary
    Settlement fakeSettlement(InMemoryInventory inventory) {
        return new Settlement(inventory);
    }

    /** The listener itself; a bean so that {@code @KafkaListener} is picked up. */
    public static class Participants {

        private final InMemoryInventory inventory;
        private final KafkaTemplate<String, Object> kafka;

        Participants(InMemoryInventory inventory, KafkaTemplate<String, Object> kafka) {
            this.inventory = inventory;
            this.kafka = kafka;
        }

        @KafkaListener(
                topics = KafkaTopics.ORDERS_CREATED,
                groupId = "it-fake-inventory-and-payment",
                properties = "spring.json.value.default.type=com.ecomdemo.messaging.OrderCreatedEvent")
        void onOrderCreated(OrderCreatedEvent order) throws Exception {
            String key = String.valueOf(order.orderId());
            try {
                if (lost(order, LOSE_ORDER_CREATED)) {
                    return;
                }

                // inventory-service's step: all or nothing. Synchronised because the listener runs
                // one thread per partition, and "check every line, then take every line" must not
                // interleave with another order's - the real service holds row locks for the same
                // reason.
                String rejection = reserveAll(order);
                if (rejection != null) {
                    kafka.send(KafkaTopics.STOCK_REJECTED, key, new StockRejectedEvent(
                            UUID.randomUUID(), order.orderId(), rejection, Instant.now())).get();
                    return;
                }
                if (lost(order, LOSE_STOCK_RESERVED)) {
                    return;
                }

                // payment-service's step, which - like the real one - never charges an order twice
                // and never charges one the saga deadline has voided.
                SagaParticipants.PaymentVerdict payment = pay(order);
                if (payment == null || lost(order, LOSE_PAYMENT_REPLY)) {
                    return;
                }
                if (!payment.paid()) {
                    // ...and its compensation: inventory's reaction to a decline.
                    releaseAll(order.orderId(), inventory);
                    kafka.send(KafkaTopics.PAYMENTS_FAILED, key, new PaymentFailedEvent(
                            UUID.randomUUID(), order.orderId(), order.totalAmount(),
                            payment.reason(), Instant.now())).get();
                    return;
                }
                kafka.send(KafkaTopics.PAYMENTS_COMPLETED, key, new PaymentCompletedEvent(
                        UUID.randomUUID(), order.orderId(), PAYMENT_IDS.getAndIncrement(),
                        order.totalAmount(), Instant.now())).get();
            } finally {
                SEEN.add(order.orderId());
            }
        }

        private static boolean lost(OrderCreatedEvent order, Set<Long> products) {
            return order.lines().stream().anyMatch(line -> products.contains(line.productId()));
        }

        /** One lock for reserving, releasing and closing - the real service's order lock. */
        private String reserveAll(OrderCreatedEvent order) {
            synchronized (FakeSagaParticipants.class) {
                if (CLOSED.contains(order.orderId())) {
                    return "The order was closed before its stock could be reserved";
                }
                try {
                    order.lines().forEach(line ->
                            inventory.requireAvailable(line.productId(), line.productName(), line.quantity()));
                } catch (InsufficientStockException e) {
                    return e.getMessage();
                }
                order.lines().forEach(line ->
                        inventory.reserve(line.productId(), line.productName(), line.quantity()));
                RESERVED.put(order.orderId(), order.lines());
                return null;
            }
        }

        /** @return the new payment, or null if the order already had one (a void, or a replay) */
        private static SagaParticipants.PaymentVerdict pay(OrderCreatedEvent order) {
            SagaParticipants.PaymentVerdict decided = order.totalAmount().compareTo(DECLINE_ABOVE) > 0
                    ? new SagaParticipants.PaymentVerdict(
                            SagaParticipants.PaymentVerdict.Status.FAILED,
                            "Payment declined: %s exceeds the limit of %s".formatted(
                                    order.totalAmount().toPlainString(), DECLINE_ABOVE.toPlainString()))
                    : new SagaParticipants.PaymentVerdict(SagaParticipants.PaymentVerdict.Status.COMPLETED, null);
            return PAYMENTS.putIfAbsent(order.orderId(), decided) == null ? decided : null;
        }
    }

    /** Gives back whatever an order holds, once. */
    static synchronized void releaseAll(Long orderId, InMemoryInventory inventory) {
        List<OrderCreatedEvent.Line> held = RESERVED.remove(orderId);
        if (held != null) {
            held.forEach(line -> inventory.release(line.productId(), line.quantity()));
        }
    }

    /** payment-service's settlement and inventory-service's close, as the saga deadline sees them. */
    public static class Settlement implements SagaParticipants {

        private final InMemoryInventory inventory;

        Settlement(InMemoryInventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public PaymentVerdict settlePayment(Long orderId, BigDecimal amount) {
            if (PAYMENT_UNREACHABLE.get()) {
                throw new ParticipantUnavailableException("payment-service timed out (fake)", null);
            }
            return PAYMENTS.computeIfAbsent(orderId, id -> new PaymentVerdict(
                    PaymentVerdict.Status.VOIDED,
                    "Payment was not attempted before the order's deadline; the order was cancelled"));
        }

        @Override
        public void closeStock(Long orderId, String reason) {
            synchronized (FakeSagaParticipants.class) {
                CLOSED.add(orderId);
                releaseAll(orderId, inventory);
            }
        }
    }
}

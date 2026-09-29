package com.ecomdemo.order.internal.saga;

import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.order.Order;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.internal.OrderRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two ways an order leaves PENDING, whoever decides it (Phase 32).
 *
 * <p>Until this phase only the saga's replies decided an order, in {@link OrderSagaHandler}. The
 * saga deadline ({@link SagaReconciler}) now decides some too, and it must decide them the SAME
 * way: a confirmation it makes must announce OrderPlaced exactly as one made by a
 * PaymentCompleted does, or notification-service would thank some shoppers and not others. So the
 * decision lives here, once, and both callers use it.
 *
 * <p>{@code MANDATORY} rather than {@code REQUIRED}: each caller has its own transaction, which
 * holds the other rows that must commit with the decision (the handler's {@code processed_event}
 * claim), and a call without one is a bug to be reported, not papered over with a new transaction.
 */
@Component
class OrderDecisions {

    private static final Logger log = LoggerFactory.getLogger(OrderDecisions.class);

    private final OrderRepository orders;
    private final OutboxWriter outbox;

    OrderDecisions(OrderRepository orders, OutboxWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    /**
     * PENDING → CONFIRMED, and only now is the order "placed".
     *
     * @return whether this call moved the order; false if it had already been decided
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean confirm(Long orderId, String decidedBy) {
        if (orders.transition(orderId, OrderStatus.CONFIRMED, null, Instant.now()) == 0) {
            log.warn("Confirmation of order {} ({}) ignored: it is no longer PENDING", orderId, decidedBy);
            return false;
        }

        // The notification that says "thank you, your order has been placed" is driven by this
        // event, so it is published on CONFIRMATION - not at checkout, as it was before the saga,
        // when a shopper could have been thanked for an order that was then cancelled. Same
        // contract, same topic; notification-service did not have to change.
        Order order = orders.findByIdWithItems(orderId).orElseThrow();
        outbox.append(OrderPlacedEvent.of(
                order.getId(),
                order.getUsername(),
                order.getTotalAmount(),
                order.getItems().size(),
                order.getPlacedAt()));
        log.info("Order {} CONFIRMED ({})", orderId, decidedBy);
        return true;
    }

    /**
     * PENDING → CANCELLED, with the reason the shopper will read.
     *
     * @return whether this call moved the order; false if it had already been decided
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean cancel(Long orderId, String reason) {
        if (orders.transition(orderId, OrderStatus.CANCELLED, reason, Instant.now()) == 0) {
            log.warn("Cancellation of order {} ignored: it is no longer PENDING", orderId);
            return false;
        }
        log.info("Order {} CANCELLED: {}", orderId, reason);
        return true;
    }
}

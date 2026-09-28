package com.ecomdemo.order.internal.saga;

import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.order.Order;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.internal.OrderRepository;
import com.ecomdemo.outbox.ProcessedEvents;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga's last step, in the service where it began: the replies that decide an order's fate.
 *
 * <p>Three replies, two outcomes. Each is one local transaction - the {@code processed_event}
 * claim, the status change, and (for a confirmation) the outbox row announcing it - as in every
 * other saga step.
 *
 * <h2>Choreography: nobody told this service what to wait for</h2>
 *
 * <p>There is no orchestrator holding a list of steps. This service published
 * {@code OrderCreated} and then simply reacts to whatever comes back. That is why the state
 * machine is guarded in the database ({@link OrderRepository#transition}) rather than by knowing
 * "what step we are on": a reply for an order that is no longer PENDING - a duplicate, or one
 * that arrives after another reply has already decided the order - moves nothing.
 */
@Service
public class OrderSagaHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaHandler.class);

    private final ProcessedEvents processedEvents;
    private final OrderRepository orders;
    private final OutboxWriter outbox;

    public OrderSagaHandler(
            ProcessedEvents processedEvents, OrderRepository orders, OutboxWriter outbox) {
        this.processedEvents = processedEvents;
        this.orders = orders;
        this.outbox = outbox;
    }

    /** Payment went through: the order is CONFIRMED, and now - only now - it is "placed". */
    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        if (!claim(event.eventId(), event, event.orderId())) {
            return;
        }
        if (orders.transition(event.orderId(), OrderStatus.CONFIRMED, null, Instant.now()) == 0) {
            log.warn("PaymentCompleted for order {} ignored: it is no longer PENDING", event.orderId());
            return;
        }

        // The notification that says "thank you, your order has been placed" is driven by this
        // event, so it is published on CONFIRMATION - not at checkout, as it was before the saga,
        // when a shopper could have been thanked for an order that was then cancelled. Same
        // contract, same topic; notification-service did not have to change.
        Order order = orders.findByIdWithItems(event.orderId()).orElseThrow();
        outbox.append(OrderPlacedEvent.of(
                order.getId(),
                order.getUsername(),
                order.getTotalAmount(),
                order.getItems().size(),
                order.getPlacedAt()));
        log.info("Order {} CONFIRMED (payment {})", event.orderId(), event.paymentId());
    }

    /** Stock could not be reserved. Nothing was taken, so cancelling is all there is to do. */
    @Transactional
    public void onStockRejected(StockRejectedEvent event) {
        if (claim(event.eventId(), event, event.orderId())) {
            cancel(event.orderId(), event.reason());
        }
    }

    /**
     * Payment was declined. Cancel the order. The stock is given back by inventory-service, which
     * reads the same event - this service neither asks for that nor waits for it.
     */
    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event) {
        if (claim(event.eventId(), event, event.orderId())) {
            cancel(event.orderId(), event.reason());
        }
    }

    private boolean claim(UUID eventId, Object event, Long orderId) {
        String type = event.getClass().getSimpleName();
        if (processedEvents.claim(eventId, type)) {
            return true;
        }
        log.info("Ignoring {} {} for order {}: already handled", type, eventId, orderId);
        return false;
    }

    private void cancel(Long orderId, String reason) {
        if (orders.transition(orderId, OrderStatus.CANCELLED, reason, Instant.now()) == 0) {
            log.warn("Cancellation of order {} ignored: it is no longer PENDING", orderId);
            return;
        }
        log.info("Order {} CANCELLED: {}", orderId, reason);
    }
}

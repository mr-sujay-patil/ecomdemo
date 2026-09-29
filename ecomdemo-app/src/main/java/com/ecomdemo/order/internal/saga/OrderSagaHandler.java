package com.ecomdemo.order.internal.saga;

import com.ecomdemo.outbox.ProcessedEvents;
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
 * machine is guarded in the database ({@code OrderRepository#transition}) rather than by knowing
 * "what step we are on": a reply for an order that is no longer PENDING - a duplicate, or one
 * that arrives after another reply has already decided the order - moves nothing.
 */
@Service
public class OrderSagaHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaHandler.class);

    private final ProcessedEvents processedEvents;
    private final OrderDecisions decisions;

    OrderSagaHandler(ProcessedEvents processedEvents, OrderDecisions decisions) {
        this.processedEvents = processedEvents;
        this.decisions = decisions;
    }

    /** Payment went through: the order is CONFIRMED, and now - only now - it is "placed". */
    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        if (claim(event.eventId(), event, event.orderId())) {
            decisions.confirm(event.orderId(), "payment " + event.paymentId());
        }
    }

    /** Stock could not be reserved. Nothing was taken, so cancelling is all there is to do. */
    @Transactional
    public void onStockRejected(StockRejectedEvent event) {
        if (claim(event.eventId(), event, event.orderId())) {
            decisions.cancel(event.orderId(), event.reason());
        }
    }

    /**
     * Payment was declined. Cancel the order. The stock is given back by inventory-service, which
     * reads the same event - this service neither asks for that nor waits for it.
     */
    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event) {
        if (claim(event.eventId(), event, event.orderId())) {
            decisions.cancel(event.orderId(), event.reason());
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
}

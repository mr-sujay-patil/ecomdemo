package com.ecomdemo.inventory.saga;

import com.ecomdemo.inventory.InventoryService;
import com.ecomdemo.inventory.ReservationLine;
import com.ecomdemo.inventory.ReservationResult;
import com.ecomdemo.outbox.Outbox;
import com.ecomdemo.outbox.ProcessedEvents;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * inventory-service's two steps in the saga, each as ONE local transaction.
 *
 * <p>Every step of a choreographed saga has the same three parts, and they must commit together:
 *
 * <ol>
 *   <li><strong>the claim</strong> - {@code processed_event}, so a redelivered message is
 *       recognised and does nothing;
 *   <li><strong>the change</strong> - here, stock held or given back;
 *   <li><strong>the announcement</strong> - the outbox row for the next step.
 * </ol>
 *
 * <p>Commit all three or none. A change without its announcement is a saga that stops dead with
 * stock held for an order nobody will ever confirm; an announcement without its change is a
 * payment for stock that was never reserved; a change without its claim is stock taken twice
 * when Kafka redelivers. This is the same "two writes, one transaction" argument as Phase 18's
 * outbox, extended by one more row.
 */
@Service
public class InventorySagaHandler {

    private static final Logger log = LoggerFactory.getLogger(InventorySagaHandler.class);

    /** What every inventory event is about: an order's stock. Keyed by the order id. */
    static final String AGGREGATE = "StockReservation";

    private final ProcessedEvents processedEvents;
    private final InventoryService inventory;
    private final Outbox outbox;

    public InventorySagaHandler(
            ProcessedEvents processedEvents, InventoryService inventory, Outbox outbox) {
        this.processedEvents = processedEvents;
        this.inventory = inventory;
        this.outbox = outbox;
    }

    /** Step 2: an order was created. Reserve it all, or say why not. */
    @Transactional
    public void onOrderCreated(OrderCreatedEvent event) {
        if (!processedEvents.claim(event.eventId(), OrderCreatedEvent.class.getSimpleName())) {
            log.info("Ignoring OrderCreatedEvent {} for order {}: already handled",
                    event.eventId(), event.orderId());
            return;
        }

        List<ReservationLine> lines = event.lines().stream()
                .map(line -> new ReservationLine(line.productId(), line.productName(), line.quantity()))
                .toList();
        ReservationResult result = inventory.reserveForOrder(event.orderId(), lines);

        if (result.reserved()) {
            StockReservedEvent reserved = StockReservedEvent.of(event);
            outbox.append(AGGREGATE, event.orderId(), reserved.eventId(), reserved);
            log.info("Reserved stock for order {} ({} line(s))", event.orderId(), lines.size());
        } else {
            StockRejectedEvent rejected = StockRejectedEvent.of(event.orderId(), result.rejectionReason());
            outbox.append(AGGREGATE, event.orderId(), rejected.eventId(), rejected);
            log.info("Rejected order {}: {}", event.orderId(), result.rejectionReason());
        }
    }

    /**
     * The compensation: payment failed, so give the order's stock back.
     *
     * <p>No event follows it. The order service hears about the failure from payment-service
     * directly and cancels the order; waiting for a "stock released" event first would make the
     * cancellation depend on this service being up, for no gain to the shopper.
     */
    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event) {
        if (!processedEvents.claim(event.eventId(), PaymentFailedEvent.class.getSimpleName())) {
            log.info("Ignoring PaymentFailedEvent {} for order {}: already handled",
                    event.eventId(), event.orderId());
            return;
        }

        int released = inventory.releaseForOrder(event.orderId());
        log.info("Payment failed for order {}: released {} reservation(s)", event.orderId(), released);
    }
}

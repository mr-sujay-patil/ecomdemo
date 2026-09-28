package com.ecomdemo.messaging;

import com.ecomdemo.outbox.Outbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The application's order events, written to the outbox in the caller's transaction.
 *
 * <p>Until Phase 24 this class WAS the outbox. The saga needed the same machinery in
 * inventory-service and payment-service, so the machinery moved to the {@code outbox} library
 * ({@link Outbox}, whose class comment carries everything that used to be here: why
 * {@code MANDATORY}, why the payload is frozen at write time, why the mapper is not injected).
 *
 * <p>What stays is the part that is the APPLICATION's knowledge rather than the library's: which
 * aggregate an event is about, and which of its fields is the key. The order module calls a
 * method named after the event and never handles a topic, an aggregate type or a UUID.
 */
@Component
public class OutboxWriter {

    /**
     * What every order event is about. Public since Phase 19: it is the value every outbox row
     * carries in {@code aggregate_type}, so a test asserting on a row can name it.
     */
    public static final String ORDER_AGGREGATE = "Order";

    private final Outbox outbox;

    public OutboxWriter(Outbox outbox) {
        this.outbox = outbox;
    }

    /** Appends an {@link OrderCreatedEvent}, keyed by its order id: the saga's first step. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OrderCreatedEvent event) {
        outbox.append(ORDER_AGGREGATE, event.orderId(), event.eventId(), event);
    }

    /** Appends an {@link OrderPlacedEvent}, keyed by its order id: the order is CONFIRMED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OrderPlacedEvent event) {
        outbox.append(ORDER_AGGREGATE, event.orderId(), event.eventId(), event);
    }
}

package com.ecomdemo.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event into the outbox, in the caller's transaction.
 *
 * <p>This class is the entire point of the phase, and it is four lines of work. Everything
 * interesting about it is in the annotation.
 *
 * <h2>{@code Propagation.MANDATORY}, which is the whole design as an assertion</h2>
 *
 * <p>The outbox is only worth anything if the event and the business change commit together. If
 * this method ever ran in its own transaction, the result would be two transactions again — the
 * same dual-write problem, moved one layer down and much harder to see, because the code would
 * look exactly like a working outbox.
 *
 * <p>{@code MANDATORY} makes that impossible rather than merely discouraged: it joins the
 * caller's transaction and throws {@code IllegalTransactionStateException} if there isn't one.
 * The alternative, {@code REQUIRED} (the default), would silently open a second transaction and
 * produce a system that passes every test and loses events in production. This is the rare case
 * where the stricter propagation is not defensiveness but the specification.
 *
 * <p>Concretely: {@code OrderPlacementService.placeOnce()} is {@code @Transactional}, calls this,
 * and the outbox row is inserted into the same unit of work as the order, the order lines, the
 * stock reduction and the cart being emptied. If the order rolls back, so does its event — which
 * is the other half of the guarantee, and the half people forget. An event announcing an order
 * that does not exist is as wrong as an order with no event.
 *
 * <h2>Why serialisation happens here and not in the relay</h2>
 *
 * <p>The payload is the state of the order <em>as it was when the order was placed</em>. Leaving
 * the relay to serialise it later would mean re-reading entities that may have changed in the
 * meantime and publishing the current state under an event that claims to describe a past one.
 * Freezing the bytes inside the transaction makes the row a record of a fact.
 *
 * <p>It also means a serialisation failure is found while the transaction is still open, so the
 * order rolls back and the customer gets an error — instead of the relay discovering, minutes
 * later and on a background thread, that it is holding a row it can never send.
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    /** What the event is about. One aggregate today; the column exists for the next one. */
    static final String ORDER_AGGREGATE = "Order";

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends an {@link OrderPlacedEvent} to the outbox.
     *
     * @throws IllegalStateException if the event cannot be serialised, which rolls the caller's
     *     transaction back — see the class comment on why that is the right outcome
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OrderPlacedEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Unchecked, so the caller's transaction rolls back by Spring's default rules. A
            // checked exception here would commit the order and skip the event, which is the one
            // outcome this phase exists to prevent.
            throw new IllegalStateException(
                    "Could not serialise OrderPlacedEvent " + event.eventId(), e);
        }

        outbox.save(
                new OutboxEvent(
                        event.eventId(),
                        ORDER_AGGREGATE,
                        String.valueOf(event.orderId()),
                        OrderPlacedEvent.class.getSimpleName(),
                        payload));

        // DEBUG, not INFO. One of these per order says nothing a healthy system needs to report -
        // the interesting lines come from the relay, which is where something can actually go
        // wrong. It is logged at all because "was the row ever written?" is the first question
        // when a notification does not arrive.
        log.debug(
                "Outbox row written for OrderPlacedEvent {} (order {})",
                event.eventId(),
                event.orderId());
    }
}

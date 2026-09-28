package com.ecomdemo.outbox;

import com.ecomdemo.outbox.internal.OutboxEvent;
import com.ecomdemo.outbox.internal.OutboxEventRepository;
import com.ecomdemo.outbox.internal.OutboxTracing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event into the outbox, in the caller's transaction.
 *
 * <p><strong>Phase 24:</strong> this was {@code OutboxWriter} in ecomdemo-app, with one method for
 * one event. The saga gives inventory-service and payment-service the same need, so it moved into
 * this library and takes any event. Nothing else about it changed, and every word below still
 * applies to all three services.
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
 * <p>Concretely (the first caller, in ecomdemo-app): {@code OrderPlacementService.placeOnce()} is {@code @Transactional}, calls this,
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
 *
 * <h2>The mapper is the consumer's, and is not injected</h2>
 *
 * <p>Spring Boot 4 auto-configures Jackson 3 ({@code tools.jackson.databind.json.JsonMapper}),
 * which serialises every HTTP response here. The Kafka consumer reads with
 * {@link JsonDeserializer}, spring-kafka's <em>Jackson 2</em> deserializer, backed by
 * {@link JacksonUtils#enhancedObjectMapper()}. Two Jackson majors, two default configurations.
 *
 * <p>An outbox payload is a Kafka message body, so it must be written by the mapper that message
 * will be read by. The two disagree exactly where it hurts most — whether an {@code Instant} goes
 * out as an ISO-8601 string or an epoch decimal is a mapper default, not a property of the record
 * — and a mismatch fails nowhere in the build. It fails at the consumer, at run time, on a message
 * the producer considers perfectly good.
 *
 * <p>So the mapper is constructed here rather than injected. That is a deliberate exception to
 * constructor injection: which mapper writes this payload is not a configuration choice to be
 * varied per environment or per test, it is a correctness requirement tied to the deserializer on
 * the other end. Making it injectable would make it possible to get wrong.
 */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    /** The consumer's own mapper. See the class comment: this is a correctness requirement. */
    private static final ObjectMapper PAYLOAD_MAPPER = JacksonUtils.enhancedObjectMapper();

    private final OutboxEventRepository outbox;
    private final OutboxTracing tracing;

    public Outbox(OutboxEventRepository outbox, OutboxTracing tracing) {
        this.outbox = outbox;
        this.tracing = tracing;
    }

    /**
     * Appends an event to the outbox, to be published on the topic its service's
     * {@link OutboxRoutes} names for it.
     *
     * @param aggregateType what the event is about, e.g. {@code "Order"}; for a human reading the
     *     table
     * @param aggregateId which one; becomes the Kafka KEY, so every event about one aggregate lands
     *     in one partition, in the order it was written
     * @param eventId the event's own idempotency key, generated once by the producer and carried in
     *     the payload - the thing a consumer's {@link ProcessedEvents} recognises on a redelivery
     * @param event the record to serialise; its simple class name is the {@code event_type}
     * @throws IllegalStateException if the event cannot be serialised, which rolls the caller's
     *     transaction back — see the class comment on why that is the right outcome
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, Object aggregateId, UUID eventId, Object event) {
        String eventType = event.getClass().getSimpleName();
        String payload;
        try {
            payload = PAYLOAD_MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Unchecked, so the caller's transaction rolls back by Spring's default rules. A
            // checked exception here would commit the business change and skip the event, which
            // is the one outcome the outbox exists to prevent.
            throw new IllegalStateException("Could not serialise " + eventType + " " + eventId, e);
        }

        outbox.save(
                new OutboxEvent(
                        eventId,
                        aggregateType,
                        String.valueOf(aggregateId),
                        eventType,
                        payload,
                        // The caller's trace, frozen with the payload so the relay can publish
                        // in it later (Phase 23; OutboxTracing explains why it must be stored).
                        tracing.currentTraceParent()));

        // DEBUG, not INFO. One of these per event says nothing a healthy system needs to report -
        // the interesting lines come from the relay, which is where something can actually go
        // wrong. It is logged at all because "was the row ever written?" is the first question
        // when a downstream service never hears about something.
        log.debug(
                "Outbox row written for {} {} ({} {})", eventType, eventId, aggregateType, aggregateId);
    }
}

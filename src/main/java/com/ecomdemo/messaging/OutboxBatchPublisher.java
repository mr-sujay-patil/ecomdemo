package com.ecomdemo.messaging;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one batch of pending events, and records what happened.
 *
 * <p>This is the transactional half of the relay. {@link OutboxRelay} owns the timer and this
 * class owns the unit of work, and they are two beans rather than one method with two annotations
 * for the reason {@code OrderPlacementService} is separate from {@code OrderService}: Spring
 * implements {@code @Transactional} with a proxy, and a proxy is only entered when the call
 * arrives from outside the object. Keeping the trigger and the work in different beans makes it
 * impossible for a later edit to turn this into a self-call that silently runs with no
 * transaction.
 *
 * <h2>Read, send, mark — in one transaction</h2>
 *
 * <p>The batch is read, published and marked inside a single transaction. That ordering gives the
 * guarantee the phase is named for, and it is worth being exact about which way it can fail:
 *
 * <ul>
 *   <li>Send succeeds, transaction commits — the normal case, published exactly once.
 *   <li>Send succeeds, process dies before the commit — the row is still pending, so the next
 *       tick sends it AGAIN. A duplicate, which {@code processed_event} on the consumer side
 *       absorbs. This is the at-least-once seam, and it is deliberate.
 *   <li>Send fails — the row stays pending with its attempt count raised, and nothing is lost.
 * </ul>
 *
 * <p>What cannot happen is a row marked published whose bytes never reached the broker, because
 * the mark is only made after the broker has <em>acknowledged</em>. That is why
 * {@link #awaitAck(OutboxEvent)} blocks rather than attaching a callback: a callback would let
 * the transaction commit while the send was still in flight, and the only durable copy of the
 * event would be marked done before it was safe. Blocking is affordable here precisely because
 * this runs on a scheduler thread — nobody is waiting for it, which is the difference between
 * this class and the Phase 17 publisher it replaces.
 */
@Component
class OutboxBatchPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxBatchPublisher.class);

    /**
     * How long to wait for one acknowledgement.
     *
     * <p>Comfortably longer than the producer's own {@code delivery.timeout.ms} of ten seconds, so
     * that in the ordinary failure case the producer's timeout fires first and reports a real
     * Kafka error rather than this one reporting a bare {@code TimeoutException} with no cause.
     * This bound exists so a hung send cannot hold a database transaction open for ever, not to
     * do the producer's job for it.
     */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(15);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    OutboxBatchPublisher(
            OutboxEventRepository outbox,
            @Qualifier(OutboxPublisherConfig.OUTBOX_KAFKA_TEMPLATE)
                    KafkaTemplate<String, String> kafkaTemplate,
            OutboxProperties properties) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    /**
     * @return how many events were published, which the caller logs only when it is non-zero
     */
    @Transactional
    int publishPendingBatch() {
        List<OutboxEvent> pending =
                outbox.findByPublishedAtIsNullOrderByIdAsc(Limit.of(properties.batchSize()));
        if (pending.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                awaitAck(event);
                event.markPublished();
                published++;
            } catch (Exception e) {
                // Interrupting the thread's own flag has to be restored before anything else
                // swallows the fact — everything below is bookkeeping and may block.
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

                event.markFailed(e.getMessage() == null ? e.toString() : e.getMessage());
                log.error(
                        "Outbox event {} (order {}) could not be published on attempt {}: {}. It "
                                + "stays pending and will be retried; {} event(s) published before it "
                                + "this tick.",
                        event.getEventId(),
                        event.getAggregateId(),
                        event.getAttempts(),
                        e.getMessage(),
                        published);

                // STOP THE BATCH at the first failure, for two independent reasons, either of
                // which would be enough.
                //
                // Ordering. The rows are read in commit order and publishing event N+1 after
                // event N has failed would put them on the topic in the wrong order — and worse,
                // would leave N to be published later, after the event that logically follows it.
                // An outbox that reorders under failure has thrown away the one guarantee that
                // made it better than sending directly.
                //
                // Cost. A failure here almost always means the broker is unreachable, which is
                // not a property of THIS event. Continuing would spend max.block.ms on every
                // remaining row: at the defaults, a full batch against a dead broker is over
                // eight minutes of a scheduler thread achieving nothing, and eight minutes of a
                // database transaction held open while it does.
                break;
            }
        }

        // No explicit save: these are managed entities inside a transaction, so the marks are
        // flushed at commit. Calling save() would be a no-op that implies the dirty checking is
        // not trusted.
        return published;
    }

    /**
     * Sends one event and waits for the broker to acknowledge it.
     *
     * <p>The key is the aggregate id — the order id — exactly as in Phase 17, so that every event
     * about one order lands in the same partition and is read in the order it was written. The
     * key is a {@code String} here rather than a formatted {@code Long} because that is what the
     * column holds; the bytes are identical.
     */
    private void awaitAck(OutboxEvent event) throws Exception {
        kafkaTemplate
                .send(topicFor(event), event.getAggregateId(), event.getPayload())
                .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Which topic an event belongs on.
     *
     * <p>A mapping in code rather than a {@code topic} column in the table, deliberately. The
     * topic is a deployment detail: renaming one, or splitting a topic in two, should be a change
     * to this method and not a migration that rewrites history. The row records what HAPPENED;
     * where that fact gets delivered is the relay's business.
     */
    private static String topicFor(OutboxEvent event) {
        String type = event.getEventType();
        if (OrderPlacedEvent.class.getSimpleName().equals(type)) {
            return KafkaTopics.ORDERS_PLACED;
        }
        // Not a log-and-skip. An unroutable row would otherwise sit pending for ever while the
        // batch stopped dead at it, blocking every event behind it — so it fails loudly, which is
        // what a deployment that forgot to map a new event type deserves.
        throw new IllegalStateException(
                "No topic is mapped for outbox event type '" + type + "'");
    }
}

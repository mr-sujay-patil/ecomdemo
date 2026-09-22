package com.ecomdemo.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts an order on {@code orders.placed} once its transaction has committed.
 *
 * <p><strong>AFTER_COMMIT, for the reason the cache evictor uses it.</strong> Publishing inside
 * the transaction would announce an order that might still roll back — and a message cannot be
 * un-sent. A consumer would email a customer about an order that does not exist, which is worse
 * than a late notification by a margin that does not need arguing.
 *
 * <p><strong>The gap this leaves, stated plainly, because Phase 18 exists to close it.</strong>
 * Between the commit and this send there is a window in which the process can die: the order is
 * in the database, the event was never published, and nothing anywhere knows it is missing. That
 * is the dual-write problem — two systems, no shared transaction — and it cannot be fixed by
 * moving this code or by trying harder with retries. The fix is to write the event to the SAME
 * database in the SAME transaction and relay it afterwards, which is the transactional outbox and
 * is Phase 18's whole subject. Doing it the simple way first is deliberate: the outbox is hard to
 * appreciate until you can point at exactly what it buys.
 *
 * <p><strong>The key is the order id</strong>, and that is a routing decision rather than a
 * formality. Kafka hashes the key to choose a partition, and order is guaranteed only within a
 * partition — so keying by order id means every event about one order lands in the same partition
 * and is read in the order it was written, however many consumers are running. A null key would
 * spread them round-robin and leave two events about the same order racing each other in
 * different partitions.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        publish(event);
    }

    /**
     * Sends, and does not wait.
     *
     * <p>{@code send} returns a future; this method attaches a callback and returns. Blocking on
     * {@code get()} would put the broker's round trip inside the checkout request — turning a
     * Kafka hiccup into checkout latency, which is the coupling the whole asynchronous design is
     * meant to remove. The producer's own retries and {@code delivery.timeout.ms} cover the
     * transient case; the callback is there so a genuine failure is visible rather than silent.
     *
     * <p>A failure here is logged and nothing else, because there is nothing else honest to do:
     * the order is committed and the customer has been told. Throwing would not un-place it. The
     * log line names the order id so the gap is at least findable — which is exactly the
     * unsatisfying state of affairs that motivates Phase 18.
     */
    private void publish(OrderPlacedEvent event) {
        kafkaTemplate
                .send(KafkaTopics.ORDERS_PLACED, String.valueOf(event.orderId()), event)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error(
                                "Order {} was committed but its OrderPlacedEvent ({}) could not be "
                                        + "published; no notification will be sent for it",
                                event.orderId(),
                                event.eventId(),
                                failure);
                        return;
                    }
                    var metadata = result.getRecordMetadata();
                    log.info(
                            "Published OrderPlacedEvent {} for order {} to {}-{} at offset {}",
                            event.eventId(),
                            event.orderId(),
                            metadata.topic(),
                            metadata.partition(),
                            metadata.offset());
                });
    }
}

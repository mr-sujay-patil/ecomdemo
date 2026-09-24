package com.ecomdemo.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Announces that a product's stock moved, to whoever is caching the catalogue.
 *
 * <h2>Why this is Kafka now and was a method call before</h2>
 *
 * <p>Phase 16 introduced {@link ProductStockChangedEvent} as a Spring application event so that a
 * sale would evict the cached catalogue entry it had just invalidated. That worked because the
 * publisher and the listener were in one process. They are not any more, and an in-process event
 * cannot cross a process — so the same fact travels as a message.
 *
 * <p>The Phase 16 comment on that event predicted exactly this: <em>"from Phase 17 the same fact
 * is the natural thing to publish to Kafka, and nothing about this class has to change for that to
 * happen"</em>. Nothing about it did.
 *
 * <h2>AFTER_COMMIT, for the reason everything else in this project uses it</h2>
 *
 * <p>The message goes out only once the transaction that changed the stock has committed. A
 * message cannot be un-sent, so announcing a reduction that might still roll back would evict a
 * cache entry that was never stale and — worse — invite a reader to repopulate it from a row that
 * does not exist yet.
 *
 * <p><strong>What this deliberately is NOT is an outbox.</strong> Phase 18 built one for order
 * events because losing one loses a customer's notification permanently. Losing a cache eviction
 * costs one TTL of staleness on one product, which is the behaviour this whole mechanism is an
 * optimisation over. Paying for an outbox table, a relay and a poll to protect that would be
 * cargo cult — the durability machinery should sit where the loss is expensive, and this is the
 * clearest example in the project of somewhere it is not.
 */
@Component
public class StockChangePublisher {

    /**
     * {@code <aggregate>.<event>}, past tense, the same naming Phase 17 settled on: a stock level
     * CHANGED, and no consumer can decline it.
     */
    public static final String TOPIC = "inventory.stock-changed";

    private static final Logger log = LoggerFactory.getLogger(StockChangePublisher.class);

    private final ApplicationEventPublisher events;
    private final KafkaTemplate<String, Object> kafka;

    public StockChangePublisher(ApplicationEventPublisher events, KafkaTemplate<String, Object> kafka) {
        this.events = events;
        this.kafka = kafka;
    }

    /**
     * Called inside the transaction that changed the stock; delivered after it commits.
     *
     * <p>It raises a Spring event rather than sending directly, so that
     * {@code @TransactionalEventListener} can hold the send until the commit. Sending here would
     * be sending from inside the transaction, which is the thing the phase comment above rules out.
     */
    void publish(Long productId) {
        events.publishEvent(new ProductStockChangedEvent(productId));
    }

    /**
     * Keyed by product id, so every change to one product lands in the same partition and is read
     * in the order it was written. A consumer that saw two changes to one product out of order
     * would evict in the wrong sequence, which for a cache is harmless and for anything that
     * eventually reads the number is not.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void send(ProductStockChangedEvent event) {
        kafka.send(TOPIC, String.valueOf(event.productId()), event)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        // Logged and nothing else, deliberately. The stock change is committed and
                        // correct; what is lost is a cache eviction, and the entry expires on its
                        // TTL regardless. Failing the caller's request over it would turn a
                        // completed sale into an error.
                        log.warn(
                                "Could not publish a stock change for product {}; a cached "
                                        + "catalogue entry may be stale until its TTL expires",
                                event.productId(),
                                failure);
                    }
                });
    }
}

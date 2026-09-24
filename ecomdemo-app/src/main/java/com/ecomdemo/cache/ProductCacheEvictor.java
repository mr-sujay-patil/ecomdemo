package com.ecomdemo.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Drops the catalogue's cached view of a product once a checkout has really taken its stock.
 *
 * <p><strong>The problem this closes.</strong> Until this class existed, placing an order
 * decremented {@code product.stock_quantity} in the database and left both caches holding the
 * figure from before the sale — so {@code GET /api/products/{id}} advertised stock that was
 * already sold for up to its ten-minute TTL, and the listing did the same for two. A shopper could
 * read "4 in stock", add four to a cart and be refused at checkout, with the database correct and
 * unhelpful throughout.
 *
 * <p><strong>Why this is not simply {@code @CacheEvict} on the write.</strong> Phase 13 considered
 * that and rejected it, for two good reasons that still hold. The checkout write is called once
 * per line inside a transaction that may still roll back, so evicting there discards good entries
 * on every failed checkout; and it evicts <em>before</em> the commit, which opens a race where a
 * concurrent read repopulates the cache from the pre-commit row and is then wrong until the TTL —
 * strictly worse than the staleness it was meant to fix, because it is unbounded by any write.
 *
 * <p><strong>{@link TransactionPhase#AFTER_COMMIT} answers both.</strong> The listener runs only
 * if the transaction committed, so a rolled-back checkout evicts nothing; and it runs after the
 * new row is visible to everyone, so whatever repopulates the cache next reads the truth. The
 * ordering is the entire fix — the same eviction, moved from inside the transaction to just after
 * it.
 *
 * <p><strong>What it deliberately does not do.</strong> It does not write the new value into the
 * cache ({@code @CachePut} style). A put would have to build a DTO from an entity that is now
 * detached, and it would race with any other transaction that committed between the two. An
 * eviction has no value to be wrong about: the next reader pays one database query and everyone
 * after them is served from a correct entry.
 */
@Component
public class ProductCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheEvictor.class);

    private final CacheManager cacheManager;

    public ProductCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Evicts the product's own entry and the whole-catalogue listing.
     *
     * <p>Both, because they are two views of the same number. The listing is cached under a single
     * key holding every product, so one product's stock changing makes that entire entry wrong —
     * there is no finer eviction available, which is exactly why the listing is given a much
     * shorter TTL than a single product.
     *
     * <p>Failures here are logged and swallowed, matching the {@code CacheErrorHandler} in
     * {@link CacheConfig}: the order is committed and the customer has been told so, and a cache
     * that cannot be reached must not turn that into an error. The cost of a missed eviction is
     * one TTL of staleness — the very thing this class removes in the normal case, and not worth
     * failing a completed sale over.
     */
    /**
     * <h2>A Kafka listener since Phase 20b, and it used to be a Spring event</h2>
     *
     * <p>The publisher is in another process now. An {@code @TransactionalEventListener} could
     * hear a stock change while inventory was a module in this application and cannot hear one
     * from inventory-service, so the same fact arrives as a message on
     * {@code inventory.stock-changed}.
     *
     * <p><strong>The AFTER_COMMIT guarantee did not weaken; it moved.</strong> It used to be
     * enforced here, by the listener refusing to run until the publisher's transaction committed.
     * It is now enforced at the publisher, which holds the send until its own commit — so a
     * reservation that rolls back still evicts nothing. The rule survived the boundary by changing
     * which side of it is responsible.
     *
     * <p>No idempotency check, deliberately, and this is the one place in the project where that
     * is the right answer. Kafka delivers at least once, so this eviction will sometimes run twice
     * — and evicting an already-evicted entry is a no-op. Phase 17 built {@code processed_event}
     * because writing a notification twice sends two emails; there is nothing here to protect.
     * Machinery belongs where a duplicate costs something.
     */
    @KafkaListener(
            topics = STOCK_CHANGED_TOPIC,
            groupId = CACHE_GROUP,
            // Its OWN container factory. The application's default one deserialises every value as
            // an OrderPlacedEvent, which is right for the notification consumer and silently wrong
            // here - see StockChangedListenerConfig for how that failed without any lag to show it.
            containerFactory = StockChangedListenerConfig.FACTORY)
    public void onStockChanged(ProductStockChanged event) {
        try {
            evict(CacheNames.PRODUCT, event.productId());
            evict(CacheNames.PRODUCT_LIST, CacheNames.PRODUCT_LIST_KEY);
        } catch (RuntimeException ex) {
            log.warn(
                    "Could not evict the catalogue caches after stock changed for product {}; "
                            + "the catalogue will be stale until the entry expires",
                    event.productId(),
                    ex);
        }
    }

    /**
     * The topic inventory-service publishes stock changes to.
     *
     * <p>Named here as a constant for the reason {@code KafkaTopics} gives: a topic name is a
     * public interface with no compiler behind it, and a typo produces a consumer that hears
     * nothing rather than an error.
     */
    static final String STOCK_CHANGED_TOPIC = "inventory.stock-changed";

    /**
     * The consumer group. Separate from the notification consumer's, because a group is a unit of
     * work-sharing: two listeners in one group would each see only some of the messages, and the
     * cache would be evicted for some products and not others.
     */
    static final String CACHE_GROUP = "ecomdemo-catalogue-cache";

    /**
     * The message, as this side reads it.
     *
     * <p>Deliberately a LOCAL record rather than a shared type imported from inventory-service.
     * Sharing the class would make the two services compile against one definition, which is a
     * compile-time coupling between things that are supposed to be independently deployable — and
     * it is exactly the mistake that makes a distributed monolith. Each side declares the shape it
     * needs; the contract is the JSON on the topic, not a jar.
     */
    record ProductStockChanged(Long productId) {}

    private void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            // evict(), not evictIfPresent(): the latter asks Redis whether the key was there,
            // which costs a round trip to learn something nobody acts on. Evicting a key that is
            // already absent is a no-op everywhere.
            cache.evict(key);
        }
    }
}

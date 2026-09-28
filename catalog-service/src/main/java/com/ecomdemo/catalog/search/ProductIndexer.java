package com.ecomdemo.catalog.search;

import com.ecomdemo.catalog.Product;
import com.ecomdemo.catalog.ProductChanged;
import com.ecomdemo.catalog.ProductService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps a product's embedding in step with the product (Phase 28).
 *
 * <h2>The path of one edit</h2>
 *
 * {@code ProductService.update} saves the row AND writes {@link ProductChanged} to the outbox, in
 * one transaction. The outbox relay publishes it to {@code catalog.product-changed} within about a
 * second. This listener reads the product as it is NOW and re-embeds it. So an edit becomes
 * searchable a second or two after it commits - eventually consistent, the same trade the cache
 * eviction made in Phase 20b - and an edit that rolled back never reaches here at all.
 *
 * <h2>Why a service listens to its own events</h2>
 *
 * Calling the model inside {@code update} would put a network call of up to 30 seconds inside a
 * database transaction (Phase 27 kept its model call outside one for exactly this reason), and a
 * model outage would make every product edit fail. Through the outbox, an edit never waits for
 * the model and never fails because of it; the embedding catches up. And the event is on Kafka
 * for any other service that later wants to know a product changed.
 *
 * <h2>Why no idempotency ledger</h2>
 *
 * Re-embedding the current state twice produces the same row twice: harmless. Two relays (there
 * are two catalog pods in Kubernetes) can send an event twice; the cost is one extra embedding
 * call, not a wrong index. The consumer GROUP is shared by all pods, so each event is handled by
 * one of them - unlike the cache evictor's group, where every pod must hear every event.
 *
 * <h2>Failures</h2>
 *
 * A model error is thrown, so the container's error handler retries it (three attempts, a second
 * apart) and then dead-letters it to {@code catalog.product-changed-dlt}. A longer outage leaves
 * those products un-indexed until the backfill is run, which is what the backfill is for.
 */
@Component
class ProductIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexer.class);

    /** One group for all pods: work-sharing, not broadcast. */
    static final String GROUP = "catalog-search-indexer";

    private final ProductService products;
    private final ProductSearchIndex index;
    private final MeterRegistry meterRegistry;

    ProductIndexer(ProductService products, ProductSearchIndex index, MeterRegistry meterRegistry) {
        this.products = products;
        this.index = index;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = ProductChanged.TOPIC,
            groupId = GROUP,
            properties = "spring.json.value.default.type=com.ecomdemo.catalog.ProductChanged")
    void onProductChanged(ProductChanged event) {
        refresh(event.productId());
    }

    /** Brings one product's embedding up to date with its row, whatever happened to it. */
    void refresh(Long productId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        if (!index.isConfigured()) {
            // Not an error: the event is acknowledged and the backfill indexes everything once a
            // model is configured. Retrying would only fill the dead-letter topic.
            index.record(sample, "not_configured");
            return;
        }
        Optional<Product> product = products.findProduct(productId);
        try {
            if (product.isPresent()) {
                index.upsert(List.of(IndexedProduct.from(product.get())));
                index.record(sample, "indexed");
                log.debug("Indexed product {}", productId);
            } else {
                index.remove(productId);
                index.record(sample, "removed");
            }
        } catch (DataIntegrityViolationException e) {
            // Deleted between the read and the write: the foreign key refused an embedding for a
            // product that no longer exists, which is exactly the right outcome.
            index.record(sample, "removed");
            log.debug("Product {} was deleted while it was being indexed", productId);
        } catch (RuntimeException e) {
            index.record(sample, "failed");
            log.warn("Indexing product {} failed: {}", productId, e.toString());
            throw e;
        }
    }
}

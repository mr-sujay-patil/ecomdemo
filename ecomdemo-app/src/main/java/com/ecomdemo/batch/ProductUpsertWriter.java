package com.ecomdemo.batch;

import com.ecomdemo.cache.CacheNames;
import com.ecomdemo.catalog.Product;
import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.inventory.InventoryClient;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * The last third of the chunk: save the products, and — once the step is over — tell the cache.
 *
 * <h2>Why the writer takes a whole chunk</h2>
 *
 * <p>{@link ItemWriter#write(Chunk)} is handed every item of the chunk at once, not one at a
 * time, and that signature is the framework's central design choice. It is what lets a writer
 * issue one batched statement instead of a hundred, and it is where the transaction boundary
 * sits: these hundred rows commit together or not at all.
 *
 * <h2>The cache</h2>
 *
 * <p>An import that writes straight to the database behind the Phase 13 cache would leave
 * {@code productList::all} serving the pre-import catalogue until its TTL ran out, and every
 * {@code product::id} the import changed serving the old price. So this writer evicts — but
 * <strong>after the step finishes</strong>, not inside {@link #write(Chunk)}.
 *
 * <p>That timing is the same evict-before-commit hazard {@code ProductService.save} describes:
 * inside the chunk transaction the new values are not visible to anyone else yet, so a
 * concurrent catalogue read could repopulate the cache from the OLD row and leave it wrong until
 * the TTL expired — a cache that is stale <em>because</em> it was evicted. Waiting until
 * {@code afterStep} costs a window in which the catalogue is briefly stale, and buys the
 * guarantee that what lands in the cache afterwards is what is really in the database.
 *
 * <p>Only the ids of products that <em>already existed</em> are collected: a product this import
 * created cannot have a cache entry, so evicting its id would be a wasted round trip per new
 * row. The listing is evicted by its one key. Note what is NOT used here — {@code Cache.clear()},
 * which on this Redis setup reported success while the keys survived (Phase 13); eviction by
 * explicit key is what this application relies on and what its tests prove.
 *
 * <p>The writer is {@code @StepScope} (see {@code ProductImportJobConfig}), so
 * {@link #updatedIds} belongs to one step execution. A singleton would have two concurrent
 * imports sharing — and clearing — each other's set.
 */
class ProductUpsertWriter implements ItemWriter<ImportedProduct>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ProductUpsertWriter.class);

    private final ProductService catalogue;
    private final InventoryClient inventory;
    private final CacheManager cacheManager;
    private final Set<Long> updatedIds = new LinkedHashSet<>();

    ProductUpsertWriter(ProductService catalogue, InventoryClient inventory,
            CacheManager cacheManager) {
        this.catalogue = catalogue;
        this.inventory = inventory;
        this.cacheManager = cacheManager;
    }

    @Override
    public void write(Chunk<? extends ImportedProduct> chunk) {
        List<Product> products = chunk.getItems().stream().map(ImportedProduct::product).toList();

        for (Product product : products) {
            if (product.getId() != null) {
                updatedIds.add(product.getId());
            }
        }

        // The products first, because an insert has no id until it is saved and stock is keyed by
        // product id. Both writes are in this chunk's transaction, so a failure rolls the pair
        // back together - which is the LAST time that will be true. Once the catalogue and the
        // inventory are separate services these become two calls to two databases with no shared
        // transaction, and the partial-failure window is recorded as a known gap rather than
        // pretended away.
        catalogue.saveAll(products);

        for (ImportedProduct imported : chunk) {
            inventory.setStockLevel(imported.product().getId(), imported.stockQuantity());
            updatedIds.add(imported.product().getId());
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        evict(CacheNames.PRODUCT_LIST, "all");
        updatedIds.forEach(id -> evict(CacheNames.PRODUCT, id));
        log.info("import evicted the catalogue listing and {} product cache entries",
                updatedIds.size());
        // null means "leave the step's own exit status alone". Returning anything else here would
        // let a cache concern change the outcome of a data job.
        return null;
    }

    private void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }
}

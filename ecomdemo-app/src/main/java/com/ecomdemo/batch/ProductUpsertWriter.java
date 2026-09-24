package com.ecomdemo.batch;

import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.ProductUpsert;
import com.ecomdemo.clients.inventory.InventoryGateway;
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

    private final CatalogGateway catalogue;
    private final InventoryGateway inventory;
    private final Set<Long> updatedIds = new LinkedHashSet<>();

    ProductUpsertWriter(CatalogGateway catalogue, InventoryGateway inventory) {
        this.catalogue = catalogue;
        this.inventory = inventory;
    }

    @Override
    public void write(Chunk<? extends ImportedProduct> chunk) {
        List<ProductUpsert> products = chunk.getItems().stream().map(ImportedProduct::product).toList();

        // ⚠️ THE CHUNK'S TRANSACTION NO LONGER COVERS BOTH WRITES, and this is the known gap the
        // plan predicted. It used to read: "Both writes are in this chunk's transaction, so a
        // failure rolls the pair back together - which is the LAST time that will be true."
        //
        // This is that moment. The catalogue write and the stock write are now two HTTP calls to
        // two services with two databases and no shared transaction. If the first succeeds and the
        // second fails, the import has created products with no stock: visible in the catalogue,
        // unbuyable, and nothing rolls them back.
        //
        // That is tolerated rather than solved, deliberately. The alternatives are a second outbox
        // (a phase of its own) or a saga across an import of ten thousand rows (more machinery than
        // the problem deserves). What makes it tolerable is that the import is RESTARTABLE and
        // IDEMPOTENT: re-running it sets the same stock levels again, so the repair is to run it
        // again rather than to reconcile by hand. Recorded in docs/decisions.md.
        List<ProductSnapshot> saved = catalogue.upsertAll(products);

        // The catalogue first, because a created product has no id until it is written and stock is
        // keyed by product id. `saved` comes back in request order, which is what lets the stock
        // level for row N be matched to the id of row N.
        for (int i = 0; i < saved.size(); i++) {
            inventory.setStockLevel(saved.get(i).id(), chunk.getItems().get(i).stockQuantity());
            updatedIds.add(saved.get(i).id());
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        // THE CACHE EVICTION THAT USED TO BE HERE IS GONE, and its absence is the split working.
        //
        // This method evicted the catalogue listing and every touched product by hand, reaching
        // into a CacheManager from a different module. It had to: the import wrote products through
        // the repository and went round the service that would have evicted them.
        //
        // It cannot go round anything now - it writes over HTTP, through catalog-service, which
        // clears its own caches as part of the same call. The cache belongs to the service that
        // owns the data, and so does the duty of keeping it honest. A caller that has to remember
        // to invalidate someone else's cache is a caller that will one day forget.
        log.info("import wrote {} product(s); catalog-service evicted its own caches", updatedIds.size());
        // null means "leave the step's own exit status alone".
        return null;
    }
}

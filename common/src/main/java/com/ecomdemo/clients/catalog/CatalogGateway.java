package com.ecomdemo.clients.catalog;

import java.util.List;

/**
 * What a caller needs from catalog-service, expressed as a Java interface.
 *
 * <p>The seam exists for the same reason {@code InventoryGateway} does: the callers — a cart adding
 * a line, a CSV import upserting rows — should change their WIRING when the catalogue becomes a
 * separate service, not their logic. An interface also gives the test suite somewhere to put a fake
 * that behaves like the real thing without a second application running.
 *
 * <p><strong>It deals in snapshots, not entities.</strong> There is no {@code Product} here. The
 * caller cannot hold catalog-service's entity, because the entity belongs to another service's
 * persistence context and another service's database — and the moment a caller holds one, somebody
 * calls a setter on it and expects that to mean something.
 */
public interface CatalogGateway {

    /** The whole catalogue. */
    List<ProductSnapshot> findAll();

    /** The product, or a {@code NotFoundException} if the catalogue has no such id. */
    ProductSnapshot requireProduct(Long productId);

    /** Creates one product and returns it with its generated id. */
    ProductSnapshot create(ProductWrite product);

    /** Replaces one product. */
    ProductSnapshot update(Long productId, ProductWrite product);

    /** Removes one product. */
    void delete(Long productId);

    /** Creates or updates products, returning them with their ids filled in. */
    List<ProductSnapshot> upsertAll(List<ProductUpsert> products);

    // NOTE: there is deliberately no `evictAll`. The CSV import used to evict the catalogue's
    // caches by hand, because it wrote products through the repository and went round the service
    // that would have evicted them. It cannot do that any more - it writes over HTTP, through the
    // service, which evicts its own caches. The cache belongs to catalog-service and so does the
    // responsibility for keeping it honest. One fewer thing for a caller to remember is the good
    // kind of consequence.
}

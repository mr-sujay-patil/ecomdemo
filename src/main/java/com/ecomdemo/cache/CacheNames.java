package com.ecomdemo.cache;

/**
 * The caches this application keeps, named once so that a typo in an annotation cannot quietly
 * create a third one.
 *
 * <p>Spring creates a cache on first use, so {@code @Cacheable("prodcuts")} does not fail — it
 * populates a cache nobody ever evicts, and the stale entries live until their TTL. Constants
 * make that impossible, and {@link CacheConfig} builds its TTL map from these same names.
 */
public final class CacheNames {

    /** One product, by id. Holds {@code ProductResponse}, never the JPA entity. */
    public static final String PRODUCT = "product";

    /** The whole catalogue, under a single key. Holds {@code List<ProductResponse>}. */
    public static final String PRODUCT_LIST = "productList";

    private CacheNames() {
    }
}

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

    /**
     * The single key the whole catalogue listing is cached under.
     *
     * <p>A constant because it is now named from two places that the compiler cannot connect: the
     * SpEL string {@code key = "'all'"} on {@code ProductService.findAll}, and
     * {@code ProductCacheEvictor}, which evicts it as an ordinary Java value. A SpEL expression is
     * a string literal to the compiler, so the two can only be kept in step by convention — and
     * the failure if they drift is silent, which is the kind this project keeps trying to turn
     * into something a test can see.
     */
    public static final String PRODUCT_LIST_KEY = "all";

    private CacheNames() {
    }
}

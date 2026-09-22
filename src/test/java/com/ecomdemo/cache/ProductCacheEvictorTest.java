package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.catalog.ProductStockChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * What the evictor evicts, and what it does when it cannot.
 *
 * <p>The ordering — that this runs after the commit and not during it — is not visible from here:
 * it is an annotation, and an annotation only means something inside a container. That half is
 * proven in {@code CacheApiIT} against a real transaction and a real Redis. What is worth pinning
 * down at this level is cheaper and just as easy to get wrong: that BOTH caches are dropped, and
 * that a cache failure cannot turn a committed order into an error.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCacheEvictor")
class ProductCacheEvictorTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache productCache;

    @Mock
    private Cache listingCache;

    @Test
    @DisplayName("evicts the product's own entry and the whole-catalogue listing")
    void evictsBothCaches() {
        when(cacheManager.getCache(CacheNames.PRODUCT)).thenReturn(productCache);
        when(cacheManager.getCache(CacheNames.PRODUCT_LIST)).thenReturn(listingCache);

        new ProductCacheEvictor(cacheManager).onStockChanged(new ProductStockChangedEvent(42L));

        verify(productCache).evict(42L);
        // The listing is one cache entry holding every product, so one product's stock moving
        // makes the whole entry wrong. Evicting only the single product would leave the
        // catalogue page — the page a shopper actually browses — showing the old number.
        verify(listingCache).evict(CacheNames.PRODUCT_LIST_KEY);
    }

    @Test
    @DisplayName("does nothing when a cache is not configured, rather than failing")
    void toleratesAMissingCache() {
        // `spring.cache.type=none` in the test profile makes getCache() return a no-op cache, and
        // a misconfiguration could make it return null. Neither is worth an exception on a path
        // that runs after an order has been committed and acknowledged.
        when(cacheManager.getCache(any())).thenReturn(null);

        assertThatCode(
                        () ->
                                new ProductCacheEvictor(cacheManager)
                                        .onStockChanged(new ProductStockChangedEvent(42L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("swallows a cache failure: the order is already committed and the customer told")
    void survivesRedisBeingDown() {
        when(cacheManager.getCache(CacheNames.PRODUCT)).thenReturn(productCache);
        doThrow(new IllegalStateException("Redis is down")).when(productCache).evict(42L);

        // The cost of this is one TTL of staleness — exactly what this class removes in the
        // normal case, and not a reason to fail a sale that has already happened. It matches the
        // CacheErrorHandler in CacheConfig, which made the same choice for reads and writes.
        assertThatCode(
                        () ->
                                new ProductCacheEvictor(cacheManager)
                                        .onStockChanged(new ProductStockChangedEvent(42L)))
                .doesNotThrowAnyException();
    }
}

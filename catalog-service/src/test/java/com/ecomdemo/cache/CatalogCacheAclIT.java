package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.catalog.dto.ProductPage;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.RedisAcl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalogue cache works when catalog-service is the Redis user {@code catalog} (Phase 37), and that user can
 * touch nothing else.
 *
 * <p>The cache is this service's real one: {@link CacheConfig}'s {@code RedisCacheManager}, driven through the
 * operations the code performs ({@code @Cacheable}'s get and put, {@code @CacheEvict} of one key, and the
 * whole-cache clear of {@code @CacheEvict(allEntries = true)} and {@link ProductCacheEvictor}, which Spring Data
 * Redis does with KEYS then DEL). The catalogue's error handler would log a refused command and carry on, so the
 * proof that nothing was refused is Redis's own {@code ACL LOG}, not the absence of an exception.
 */
@DisplayName("The catalogue cache under the catalog Redis ACL user (Phase 37)")
class CatalogCacheAclIT {

    private static GenericContainer<?> redis;

    @BeforeAll
    static void start() throws Exception {
        redis = RedisAcl.start();
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    private ApplicationContextRunner catalogCache() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withUserConfiguration(CacheConfig.class)
                .withPropertyValues("spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(6379),
                        "spring.data.redis.username=catalog",
                        "spring.data.redis.password=" + RedisAcl.TEST_PASSWORD);
    }

    @Test
    @DisplayName("get, put, evict and clear work, the entries are where the smoke test looks, and Redis refused nothing")
    void theCacheWorks() {
        ProductResponse product = new ProductResponse(7L, "ACL probe", "cached as catalog", new BigDecimal("12.50"), 3,
                "TEST", null);
        catalogCache().run(context -> {
            RedisAcl.forgetRefusals(redis);
            CacheManager caches = context.getBean(CacheManager.class);
            Cache products = caches.getCache(CacheNames.PRODUCT);
            Cache listing = caches.getCache(CacheNames.PRODUCT_LIST);

            products.put(7L, product);
            assertThat(products.get(7L, ProductResponse.class)).isEqualTo(product);
            listing.put("p0:20", new ProductPage(List.of(product), 1));
            listing.put("p1:20", new ProductPage(List.of(), 1));
            assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, "EXISTS", "product::7", "productList::p0:20")).isEqualTo("2");

            products.evict(7L);
            listing.clear();
            assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, "EXISTS", "product::7", "productList::p0:20",
                    "productList::p1:20")).isEqualTo("0");

            // The stock-changed path: the real evictor, one key and the whole listing.
            products.put(8L, product);
            listing.put("p0:20", new ProductPage(List.of(product), 1));
            new ProductCacheEvictor(caches).onStockChanged(new ProductCacheEvictor.ProductStockChanged(8L));
            assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, "EXISTS", "product::8", "productList::p0:20")).isEqualTo("0");

            assertThat(RedisAcl.refusals(redis)).as("Redis's ACL LOG").isEmpty();
        });
    }

    @Test
    @DisplayName("the catalog user is refused the rate limiter's and the assistant's keys, another database and FLUSHALL")
    void theCatalogUserIsRefusedEverythingElse() throws Exception {
        assertThat(RedisAcl.cli(redis, "catalog", "GET", "assistant:memory:x")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "catalog", "GET", "request_rate_limiter.{x}.tokens")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "catalog", "FLUSHALL")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "catalog", "-n", "1", "PING")).contains("NOPERM");
    }
}

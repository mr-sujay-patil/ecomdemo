package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecomdemo.catalog.dto.ProductPage;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.RedisAcl;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalogue cache works when catalog-service is the Redis user {@code catalog} (Phase 37), and that user can
 * touch nothing else.
 *
 * <p>The cache is this service's real one: {@link CacheConfig}'s {@code RedisCacheManager}, driven through the
 * operations the code performs ({@code @Cacheable}'s get and put, {@code @CacheEvict} of one key, and the
 * whole-cache clear of {@code @CacheEvict(allEntries = true)} and {@link ProductCacheEvictor}, which this cache does
 * with SCAN then DEL since KI-074). The catalogue's error handler would log a refused command and carry on, so the
 * proof that nothing was refused is Redis's own {@code ACL LOG}, not the absence of an exception.
 *
 * <p>Two fixes are proven here too. KI-074: a clear never sends KEYS (which walks the whole keyspace in one
 * blocking call); Redis's own per-command statistics are the witness, and the {@code catalog} user no longer has
 * {@code +keys}. KI-075: a delete Redis refuses reaches {@link CacheConfig}'s error handler and
 * {@link ProductCacheEvictor}'s warning, because the cache writes synchronously; before the fix the delete ran
 * on another thread and its failure went nowhere.
 */
@DisplayName("The catalogue cache under the catalog Redis ACL user (Phase 37)")
class CatalogCacheAclIT {

    private static GenericContainer<?> redis;

    @BeforeAll
    static void start() throws Exception {
        redis = RedisAcl.start();
    }

    /** What Redis refused, in the test output: the first thing to read when a test here fails. */
    @AfterEach
    void showRefusals() throws Exception {
        System.out.println("Redis ACL LOG after the test:\n" + RedisAcl.refusals(redis));
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    private ApplicationContextRunner catalogCache() {
        return catalogCacheAs("catalog");
    }

    private ApplicationContextRunner catalogCacheAs(String user) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withUserConfiguration(CacheConfig.class)
                .withPropertyValues("spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(6379),
                        "spring.data.redis.username=" + user,
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
            // Immediate (synchronous) writes since KI-075: the keys are gone when evict() and clear() return, so
            // no waiting here. Before, Spring Data Redis 4 deleted on another thread a moment later.
            assertGone("product::7", "productList::p0:20", "productList::p1:20");

            // The stock-changed path: the real evictor, one key and the whole listing.
            products.put(8L, product);
            listing.put("p0:20", new ProductPage(List.of(product), 1));
            new ProductCacheEvictor(caches).onStockChanged(new ProductCacheEvictor.ProductStockChanged(8L));
            assertGone("product::8", "productList::p0:20");

            assertThat(RedisAcl.refusals(redis)).as("Redis's ACL LOG").isEmpty();
        });
    }

    /**
     * KI-074: the clear walks the keyspace with SCAN, a few keys per call, and never with KEYS. Redis's own
     * per-command statistics ({@code INFO commandstats}, emptied first) are the witness: a command Redis has seen,
     * even one it refused, gets a {@code cmdstat_<name>} line. A user without {@code +keys} alone would not prove it,
     * since the error handler would swallow the refusal.
     */
    @Test
    @DisplayName("KI-074: a whole-cache clear sends SCAN, never KEYS, and still removes every entry")
    void aClearScansAndNeverSendsKeys() {
        catalogCache().run(context -> {
            Cache listing = context.getBean(CacheManager.class).getCache(CacheNames.PRODUCT_LIST);
            // More entries than one SCAN batch, so the clear has to take several steps.
            List<String> keys = new ArrayList<>();
            for (int page = 0; page < 1_500; page++) {
                listing.put("p" + page + ":20", new ProductPage(List.of(), 1));
                keys.add("productList::p" + page + ":20");
            }
            assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, "EVAL", "return #redis.call('KEYS', 'productList::*')", "0"))
                    .as("the entries before the clear (counted by the admin, before the statistics are reset)")
                    .isEqualTo("1500");
            RedisAcl.forgetRefusals(redis);
            assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, "CONFIG", "RESETSTAT")).isEqualTo("OK");

            listing.clear();

            assertGone(keys.toArray(String[]::new));
            String commands = RedisAcl.cli(redis, RedisAcl.ADMIN, "INFO", "commandstats");
            assertThat(commands).as("INFO commandstats after the clear").doesNotContain("cmdstat_keys:")
                    .contains("cmdstat_scan:");
            assertThat(RedisAcl.refusals(redis)).as("Redis's ACL LOG").isEmpty();
        });
    }

    /**
     * KI-075: a delete Redis refuses is REPORTED. The user is the manifest's {@code catalog} line with the delete
     * commands taken away, so every eviction fails on the server. Through Spring's cache interceptor
     * ({@code @CacheEvict}, as {@code ProductService} uses it) the failure must reach {@link CacheConfig}'s error
     * handler, which logs it as an ERROR (a stale entry survives); through {@link ProductCacheEvictor} (no
     * interceptor) its own warning. Before the fix the delete ran asynchronously and neither ever heard of it.
     */
    @Test
    @DisplayName("KI-075: a refused eviction or clear reaches the error handler and the evictor's warning")
    void aRefusedEvictionIsReported() throws Exception {
        String user = "catalog-nodelete";
        List<String> rules = new ArrayList<>(Arrays.asList(RedisAcl.users().stream()
                .filter(line -> line.startsWith("user catalog ")).findFirst().orElseThrow()
                .substring("user catalog ".length()).split(" ")));
        rules.addAll(List.of("-del", "-unlink", ">" + RedisAcl.TEST_PASSWORD));
        List<String> setUser = new ArrayList<>(List.of("ACL", "SETUSER", user));
        setUser.addAll(rules);
        assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, setUser.toArray(String[]::new))).isEqualTo("OK");

        ProductResponse product = new ProductResponse(9L, "Stale probe", "must be reported", new BigDecimal("1.00"), 1,
                "TEST", null);
        ListAppender<ILoggingEvent> logged = capture(CacheConfig.class, ProductCacheEvictor.class);
        try {
            catalogCacheAs(user).withBean(Evictions.class).run(context -> {
                CacheManager caches = context.getBean(CacheManager.class);
                caches.getCache(CacheNames.PRODUCT).put(9L, product);
                caches.getCache(CacheNames.PRODUCT_LIST).put("p0:20", new ProductPage(List.of(product), 1));
                Evictions evictions = context.getBean(Evictions.class);

                // The error handler swallows the failure (a cache must not fail a request), so the calls succeed.
                assertThatCode(() -> evictions.evictProduct(9L)).doesNotThrowAnyException();
                assertThatCode(evictions::clearListing).doesNotThrowAnyException();
                new ProductCacheEvictor(caches).onStockChanged(new ProductCacheEvictor.ProductStockChanged(9L));

                assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, "EXISTS", "product::9", "productList::p0:20"))
                        .as("Redis really refused the deletes").isEqualTo("2");
                assertThat(RedisAcl.refusals(redis)).as("Redis's ACL LOG").contains(user);
            });
            assertThat(logged.list).as("what the cache logged")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).startsWith("cache EVICT failed on product for key 9");
                    })
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).startsWith("cache CLEAR failed on productList");
                    })
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .startsWith("Could not evict the catalogue caches after stock changed for product 9");
                    });
        } finally {
            release(logged, CacheConfig.class, ProductCacheEvictor.class);
            RedisAcl.cli(redis, RedisAcl.ADMIN, "DEL", "product::9", "productList::p0:20");
            RedisAcl.cli(redis, RedisAcl.ADMIN, "ACL", "DELUSER", user);
        }
    }

    /** The two eviction forms the service uses, through Spring's cache interceptor (and so its error handler). */
    static class Evictions {

        @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#id")
        public void evictProduct(Long id) {
        }

        @CacheEvict(cacheNames = CacheNames.PRODUCT_LIST, allEntries = true)
        public void clearListing() {
        }
    }

    private static ListAppender<ILoggingEvent> capture(Class<?>... sources) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Class<?> source : sources) {
            ((Logger) LoggerFactory.getLogger(source)).addAppender(appender);
        }
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender, Class<?>... sources) {
        for (Class<?> source : sources) {
            ((Logger) LoggerFactory.getLogger(source)).detachAppender(appender);
        }
        appender.stop();
    }

    /** The keys are gone NOW, with no waiting: the deletes are synchronous (KI-075). */
    private static void assertGone(String... keys) throws Exception {
        List<String> command = new ArrayList<>(List.of("EXISTS"));
        command.addAll(List.of(keys));
        assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, command.toArray(String[]::new))).as("EXISTS %s", keys[0])
                .isEqualTo("0");
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

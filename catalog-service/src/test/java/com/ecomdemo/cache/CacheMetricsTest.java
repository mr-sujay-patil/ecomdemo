package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.metrics.CacheMetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * KI-032: the catalogue cache's hit rate can be seen.
 *
 * <p>Boot publishes {@code cache.gets} (Prometheus: {@code cache_gets_total{cache, result}}) for a cache only
 * when one of its {@code CacheMeterBinderProvider}s accepts it, and its Redis provider accepts a
 * {@code RedisCache} and nothing else. {@link CacheConfig} wraps every cache in {@link LoggingCache}, so until
 * KI-032 nothing was published at all; and the writer collected no statistics, so a bound binder would have
 * read 0 for ever.
 *
 * <p>The real wiring: {@link CacheConfig} and Boot's own cache-metrics auto-configuration. Redis is a deep-stubbed
 * connection whose every GET answers nothing, so a read is a miss and is counted without a server.
 */
@DisplayName("the catalogue caches publish their hit and miss counts (KI-032)")
class CacheMetricsTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheMetricsAutoConfiguration.class))
            .withUserConfiguration(CacheConfig.class)
            .withBean(JsonMapper.class, JsonMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class, RETURNS_DEEP_STUBS));

    @ParameterizedTest
    @ValueSource(strings = {CacheNames.PRODUCT, CacheNames.PRODUCT_LIST})
    @DisplayName("each catalogue cache has cache.gets meters")
    void eachCacheIsBound(String name) {
        context.run(app -> assertThat(app.getBean(MeterRegistry.class).find("cache.gets").tag("cache", name).meters())
                .as("cache.gets for " + name)
                .isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {CacheNames.PRODUCT, CacheNames.PRODUCT_LIST})
    @DisplayName("and they count: a read Redis cannot answer is one miss")
    void aMissIsCounted(String name) {
        context.run(app -> {
            Cache cache = app.getBean(CacheManager.class).getCache(name);

            assertThat(cache).isInstanceOf(LoggingCache.class);
            assertThat(cache.get("1")).isNull();

            assertThat(app.getBean(MeterRegistry.class).find("cache.gets").tag("cache", name).tag("result", "miss")
                    .functionCounter())
                    .as("cache.gets{result=miss} for " + name)
                    .isNotNull()
                    .satisfies(misses -> assertThat(misses.count()).isEqualTo(1));
        });
    }
}

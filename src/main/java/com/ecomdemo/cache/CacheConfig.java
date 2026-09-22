package com.ecomdemo.cache;

import com.ecomdemo.product.dto.ProductResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

/**
 * How the cache stores things, for how long, and what happens when it breaks.
 *
 * <p><strong>Cache-aside.</strong> Spring's {@code @Cacheable} implements the cache-aside
 * pattern: look in the cache; on a miss, call the method and store what it returns. The
 * application, not Redis, owns the data — Redis never talks to PostgreSQL and knows nothing
 * about it. That is why the cache can be wiped at any moment with no consequence beyond a slow
 * minute.
 *
 * <p><strong>Everything expires.</strong> Every cache has a TTL, and that is the real answer to
 * invalidation. Explicit {@code @CacheEvict} handles the changes this application makes itself;
 * the TTL handles everything else — a migration, a manual {@code UPDATE}, a bug in an eviction
 * rule. Without one, a single missed eviction is wrong for ever.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * How long a single product may be stale.
     *
     * <p>Ten minutes is a judgement about the data, not a default. A product's name, description
     * and price change rarely and by hand; the cost of showing yesterday's description for ten
     * minutes is nil, and every edit evicts the entry anyway. The TTL is the backstop, not the
     * mechanism.
     */
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);

    /**
     * The catalogue listing expires faster, for a different reason.
     *
     * <p>It is ONE key covering every product, so any create, update or delete invalidates all of
     * it — the entry is both the most expensive to hold and the most likely to be wrong.
     */
    private static final Duration PRODUCT_LIST_TTL = Duration.ofMinutes(2);

    private final JsonMapper jsonMapper;

    public CacheConfig(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * <strong>A serializer per cache, typed to exactly what that cache holds.</strong>
     *
     * <p>The obvious approach is one generic serializer for everything, which needs Jackson's
     * <em>default typing</em> — writing the class name into each document and instantiating it on
     * read. Two things are wrong with that, and the first one bit:
     *
     * <ul>
     *   <li><strong>It did not round-trip.</strong> Default typing writes a type id for an
     *       object, but a root-level {@code List} came back as a bare JSON array with no type id
     *       at all — and the reader then demanded one:
     *       {@code Could not read JSON: Unexpected token (START_OBJECT), expected VALUE_STRING:
     *       need ... type id}. Every cached listing read failed. It wrote something it could not
     *       read back.</li>
     *   <li><strong>It is a deserialization gadget.</strong> "The document names the class to
     *       instantiate" is a well-trodden path to remote code execution; anyone who can write to
     *       Redis chooses what gets constructed. Jackson's own convenience method for it is
     *       called {@code enableUnsafeDefaultTyping}.</li>
     * </ul>
     *
     * <p>Neither problem exists here, because each cache holds exactly one type and says so. The
     * documents carry no {@code @class}, so they are smaller and there is nothing to exploit; and
     * the type is known at read time, so a {@code List<ProductResponse>} deserialises as one.
     *
     * <p>The cost is that adding a cache means adding a line here. That is a feature: it forces
     * the question "what does this cache hold?", which is the question default typing lets you
     * avoid answering until production.
     */
    private RedisCacheConfiguration configurationFor(RedisSerializer<?> valueSerializer, Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                // Plain string keys, so a human can find them: `product::1`, not a base64 blob.
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        valueSerializer))
                .entryTtl(ttl)
                // A null is a real answer, and caching it would mean caching "this product does
                // not exist" — exactly the entry that must disappear the moment somebody creates
                // it.
                .disableCachingNullValues();
    }

    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        TypeFactory types = jsonMapper.getTypeFactory();

        RedisCacheConfiguration product = configurationFor(
                new JacksonJsonRedisSerializer<>(jsonMapper, types.constructType(ProductResponse.class)),
                PRODUCT_TTL);

        RedisCacheConfiguration productList = configurationFor(
                new JacksonJsonRedisSerializer<>(
                        jsonMapper, types.constructCollectionType(List.class, ProductResponse.class)),
                PRODUCT_LIST_TTL);

        return new LoggingRedisCacheManager(
                connectionFactory,
                product,
                Map.of(CacheNames.PRODUCT, product, CacheNames.PRODUCT_LIST, productList));
    }

    /**
     * <strong>A broken cache must not break the application.</strong>
     *
     * <p>The default {@code CacheErrorHandler} rethrows, so anything wrong with Redis — it is
     * down, it is slow, an entry written by an older version of the code will not deserialise —
     * becomes a failed request. That is backwards. A cache-aside cache is an optimisation over a
     * database that is still perfectly capable of answering; losing it should cost latency, not
     * availability.
     *
     * <p>This was not hypothetical. The serializer bug above turned every catalogue read into a
     * 500 (surfacing as a 401, see {@code SecurityConfig}), for a cache that the application did
     * not need in order to answer. With this handler the same bug would have been a log line and
     * a slow request.
     *
     * <p>Note the asymmetry: a failed <em>put</em> or <em>evict</em> is logged and swallowed too,
     * and a swallowed evict means a stale entry survives. That is what the TTL is for, and it is
     * the reason every cache here has one.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("cache GET failed on {} for key {} — falling through to the database: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCachePutError(
                    RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("cache PUT failed on {} for key {} — the value is simply not cached: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                // The one that deserves a louder voice: a failed evict leaves a stale entry, and
                // only the TTL will clear it.
                log.error("cache EVICT failed on {} for key {} — a STALE entry may survive until "
                                + "its TTL expires: {}",
                        cache.getName(), key, exception.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("cache CLEAR failed on {} — stale entries may survive until their TTL "
                        + "expires: {}", cache.getName(), exception.toString());
            }
        };
    }

    /**
     * A {@link RedisCacheManager} that wraps every cache in a {@link LoggingCache}.
     *
     * <p>{@code decorateCache} is the extension point {@code AbstractCacheManager} provides for
     * exactly this, so a cache added later is logged without anyone remembering to ask for it.
     */
    private static final class LoggingRedisCacheManager extends RedisCacheManager {

        private LoggingRedisCacheManager(
                RedisConnectionFactory connectionFactory,
                RedisCacheConfiguration defaultConfiguration,
                Map<String, RedisCacheConfiguration> perCacheConfiguration) {
            super(
                    RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory),
                    defaultConfiguration,
                    perCacheConfiguration);
        }

        @Override
        protected Cache decorateCache(Cache cache) {
            return new LoggingCache(cache);
        }
    }
}

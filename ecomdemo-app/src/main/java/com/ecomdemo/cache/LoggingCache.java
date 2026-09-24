package com.ecomdemo.cache;

import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

/**
 * Wraps a cache and says whether each lookup hit or missed.
 *
 * <p>Spring Cache is deliberately silent: the whole point of {@code @Cacheable} is that calling
 * code cannot tell the difference between a hit and a miss. That is excellent for the code and
 * unhelpful for anyone trying to find out whether the cache is doing anything at all — a cache
 * with a 0% hit rate looks exactly like a cache with a 90% hit rate from the outside.
 *
 * <p>So this decorator logs at DEBUG, and {@link CacheConfig} wraps every cache in one. It is a
 * decorator rather than a subclass of {@code RedisCache} because it then works for any provider:
 * swap Redis for Caffeine and this still reports.
 *
 * <p>Enable it with:
 * <pre>logging.level.com.ecomdemo.cache=DEBUG</pre>
 *
 * <p>Note what it does NOT do: count. Hit ratios belong in metrics, not in a log somebody greps —
 * and Spring already exposes them through Micrometer, which arrives in Phase 15.
 */
public class LoggingCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(LoggingCache.class);

    private final Cache delegate;

    public LoggingCache(Cache delegate) {
        this.delegate = delegate;
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper value = delegate.get(key);
        logLookup(key, value != null);
        return value;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        T value = delegate.get(key, type);
        logLookup(key, value != null);
        return value;
    }

    /**
     * The read-through form: Spring calls this with the method it would otherwise invoke.
     *
     * <p>A miss cannot be detected by inspecting the return value here — the loader has already
     * run by the time it comes back — so the delegate is asked first and the loader is only used
     * when that finds nothing. That is also what makes the logging honest: the "miss" line is
     * written before the database is touched, not after.
     */
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper existing = delegate.get(key);
        if (existing != null) {
            logLookup(key, true);
            @SuppressWarnings("unchecked")
            T cached = (T) existing.get();
            return cached;
        }
        logLookup(key, false);
        return delegate.get(key, valueLoader);
    }

    private void logLookup(Object key, boolean hit) {
        if (log.isDebugEnabled()) {
            log.debug("cache {} {} for key {}", hit ? "HIT " : "MISS", delegate.getName(), key);
        }
    }

    @Override
    public void put(Object key, Object value) {
        log.debug("cache PUT  {} for key {}", delegate.getName(), key);
        delegate.put(key, value);
    }

    @Override
    public void evict(Object key) {
        log.debug("cache EVICT {} for key {}", delegate.getName(), key);
        delegate.evict(key);
    }

    @Override
    public void clear() {
        log.debug("cache CLEAR {}", delegate.getName());
        delegate.clear();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return delegate.evictIfPresent(key);
    }

    @Override
    public boolean invalidate() {
        return delegate.invalidate();
    }
}

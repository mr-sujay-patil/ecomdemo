package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

/**
 * KI-062: concurrent misses on one key load it once.
 *
 * <p>The smoke test's burst sends 40 catalogue reads at once into a cold page cache. Spring Data Redis's cache writer
 * does not serialise loaders unless it locks through Redis (a lock key the {@code catalog} ACL user may not touch),
 * so every one of the 40 ran the page query and called inventory-service, the slowest passed the gateway's 2 s
 * timeout, and the breaker opened. {@link LoggingCache#get(Object, Callable)} now lets one caller per key load while
 * the others wait for its value.
 *
 * <p>The delegate here behaves like that writer: check, load, put, no lock.
 */
@DisplayName("LoggingCache: a cold key read by many callers at once is loaded once (KI-062)")
class LoggingCacheTest {

    private static final int CALLERS = 20;

    @Test
    @DisplayName("20 concurrent misses on one key run the loader once, and every caller gets its value")
    void concurrentMissesLoadOnce() throws Exception {
        LoggingCache cache = new LoggingCache(new UnlockedCache());
        AtomicInteger loads = new AtomicInteger();
        Callable<String> loader = () -> {
            loads.incrementAndGet();
            Thread.sleep(200); // a page query and an inventory call: long enough for every caller to miss
            return "page";
        };

        List<String> values = callTogether(() -> cache.get("p0:50", loader));

        assertThat(values).hasSize(CALLERS).containsOnly("page");
        assertThat(loads.get()).as("loads of one cold key").isEqualTo(1);
    }

    @Test
    @DisplayName("different keys still load independently, at the same time")
    void differentKeysDoNotWaitForEachOther() throws Exception {
        LoggingCache cache = new LoggingCache(new UnlockedCache());
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger next = new AtomicInteger();

        long started = System.nanoTime();
        callTogether(() -> {
            String key = "p" + next.getAndIncrement() + ":50";
            return cache.get(key, () -> {
                loads.incrementAndGet();
                Thread.sleep(200);
                return key;
            });
        });
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(loads.get()).isEqualTo(CALLERS);
        assertThat(millis).as("20 keys loaded in parallel, not one after another (20 x 200 ms)").isLessThan(2000);
    }

    @Test
    @DisplayName("a hit never runs the loader")
    void aHitSkipsTheLoader() {
        UnlockedCache delegate = new UnlockedCache();
        delegate.put("p0:50", "cached");

        Callable<String> mustNotRun = () -> {
            throw new AssertionError("the loader ran on a hit");
        };
        String value = new LoggingCache(delegate).get("p0:50", mustNotRun);

        assertThat(value).isEqualTo("cached");
    }

    @Test
    @DisplayName("a loader that fails is reported as Spring's ValueRetrievalException, and the next caller tries again")
    void aFailedLoadIsNotCached() {
        LoggingCache cache = new LoggingCache(new UnlockedCache());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.get("p0:50", () -> {
            throw new IllegalStateException("inventory-service down");
        })).isInstanceOf(Cache.ValueRetrievalException.class).hasRootCauseMessage("inventory-service down");

        assertThat(cache.get("p0:50", () -> "page")).isEqualTo("page");
    }

    private static List<String> callTogether(Callable<String> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<String> values = new ArrayList<>();
            for (Future<String> future : futures) {
                values.add(future.get(10, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Like Spring Data Redis's writer without locking: a read-through that checks, loads and puts, unguarded. */
    private static final class UnlockedCache implements Cache {
        private final Map<Object, Object> store = new ConcurrentHashMap<>();

        @Override
        public String getName() {
            return "productList";
        }

        @Override
        public Object getNativeCache() {
            return store;
        }

        @Override
        public ValueWrapper get(Object key) {
            Object value = store.get(key);
            return value == null ? null : new SimpleValueWrapper(value);
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return type.cast(store.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> valueLoader) {
            Object value = store.get(key);
            if (value == null) {
                try {
                    value = valueLoader.call();
                } catch (Exception e) {
                    throw new ValueRetrievalException(key, valueLoader, e);
                }
                store.put(key, value);
            }
            return (T) value;
        }

        @Override
        public void put(Object key, Object value) {
            store.put(key, value);
        }

        @Override
        public void evict(Object key) {
            store.remove(key);
        }

        @Override
        public void clear() {
            store.clear();
        }
    }
}

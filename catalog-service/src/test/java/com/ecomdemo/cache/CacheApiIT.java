package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.CatalogIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the cache is actually used — against a real Redis, over real HTTP.
 *
 * <p>The technique running through this class is worth stating once. Asserting that a read is
 * "fast", or that a key exists, shows the cache was <em>written</em>; it does not show anything
 * was <em>served</em> from it. So these tests change the database <strong>behind the cache's
 * back</strong>, with direct SQL that no eviction can see, and then read through the API. If the
 * old value comes back, the read never reached PostgreSQL. That is unambiguous in a way a timing
 * assertion never is.
 */
class CacheApiIT extends CatalogIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.ecomdemo.catalog.ProductService productService;

    /**
     * <strong>Phase 20b changed what these two tests can prove, and the change is worth reading.</strong>
     *
     * <p>They used to reserve stock inside a transaction and assert that the cache was evicted on
     * a commit and untouched on a rollback. Reserving is an HTTP call to inventory-service now, so
     * that setup would need a second application running — and the thing under test was never the
     * reservation. It was the LISTENER's timing.
     *
     * <p>That timing has moved out of this application altogether: the AFTER_COMMIT guarantee is
     * enforced at the publisher in inventory-service, which holds the Kafka send until its own
     * transaction commits. It is asserted there, in {@code InventoryServiceTest}, where the
     * transaction actually is.
     *
     * <p>What is left here is the eviction itself — given the message, the entries go. The
     * evictor is driven directly, which is honest about what this test now covers rather than
     * keeping a transaction around it that no longer means anything.
     */
    @Autowired
    private ProductCacheEvictor evictor;

    @Autowired
    private com.ecomdemo.clients.inventory.InventoryGateway inventory;

    @Autowired
    private TransactionTemplate transactions;

    private final List<Long> createdIds = new ArrayList<>();

    /**
     * One caller, where there used to be two.
     *
     * <p>This class had an {@code admin} and a {@code shopper}, and emptied the shopper's cart
     * between tests so a leftover line could not fail the next checkout. Neither exists here:
     * catalog-service has no cart and cannot tell an administrator from a shopper — both arrive as
     * the application's service token. The cart housekeeping went with the three checkout tests
     * that needed it.
     */
    private TestRestTemplate admin;

    @BeforeEach
    void startFromAnEmptyCache() {
        admin = asService();
        clearCaches();
    }

    @AfterEach
    void cleanUp() {
        createdIds.forEach(id -> admin.delete("/api/products/" + id));
        createdIds.clear();
        clearCaches();
    }

    /**
     * Empties the cache by deleting the keys, not by calling {@code CacheManager.clear()}.
     *
     * <p>That is deliberate. {@code RedisCache.clear()} logged and returned without error here
     * while the keys survived, so a test built on it would "start from an empty cache" without
     * actually doing so — and would then pass or fail for reasons having nothing to do with what
     * it was checking. Deleting the keys is unambiguous, and it is also what
     * {@code redis-cli FLUSHDB} does in the smoke test.
     *
     * <p>The application itself never calls {@code clear()}: every eviction it performs names an
     * explicit key ({@code @CacheEvict(key = ...)}), and those are covered by
     * {@link #deleteEvictsTheEntry()} and {@link #updateWritesThroughToTheCache()}.
     */
    private void clearCaches() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private ProductResponse create(String name, String price, int stock) {
        ProductResponse created = admin.postForObject(
                "/api/products",
                new ProductRequest(name, name + " description", new BigDecimal(price), stock, "CACHE"),
                ProductResponse.class);
        assertThat(created).isNotNull();
        createdIds.add(created.id());
        return created;
    }

    /** The stock this product shows in the cached whole-catalogue listing. */
    private int listedStockOf(long id) {
        ProductResponse[] listing = rest.getForObject("/api/products", ProductResponse[].class);
        assertThat(listing).isNotNull();
        return java.util.Arrays.stream(listing)
                .filter(p -> p.id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("product " + id + " is not in the listing"))
                .stockQuantity();
    }

    /** Changes the row without going through the application, so nothing evicts anything. */
    private void changePriceBehindTheCache(long id, String newPrice) {
        int rows = jdbc.update("UPDATE product SET price = ? WHERE id = ?", new BigDecimal(newPrice), id);
        assertThat(rows).as("the UPDATE must actually hit the row").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", BigDecimal.class, id))
                .as("and the database must now hold the new price")
                .isEqualByComparingTo(newPrice);
    }

    @Test
    @DisplayName("a repeated read is served from the cache, not from the database")
    void repeatedReadsSkipTheDatabase() {
        ProductResponse product = create("Cached Lamp", "1500.00", 9);

        // First read: a miss, so it goes to PostgreSQL and populates the cache.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class).price())
                .isEqualByComparingTo("1500.00");

        // Now move the database out from under it. No API call, so no eviction.
        changePriceBehindTheCache(product.id(), "9999.00");

        // Second read: if this still says 1500.00, it cannot have touched the database.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class).price())
                .as("served from the cache — the database now says 9999.00")
                .isEqualByComparingTo("1500.00");

        // And with the cache emptied, the truth comes back.
        assertThat(redis.keys("product::*")).as("the entry is in Redis before clearing").isNotEmpty();
        clearCaches();
        assertThat(redis.keys("product::*")).as("and gone after clearing").isEmpty();

        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class).price())
                .as("cache cleared, so this read reaches PostgreSQL")
                .isEqualByComparingTo("9999.00");
    }

    @Test
    @DisplayName("the catalogue listing is cached under one key, and that key covers every product")
    void theListingIsCachedAsAWhole() {
        create("Cached Listing Item", "10.00", 5);

        int before = rest.getForObject("/api/products", ProductResponse[].class).length;

        // A product inserted behind the cache's back is invisible until the listing expires or is
        // evicted — the cost of caching a collection under a single key.
        // Two inserts since Phase 20, because stock lives in its own table. The listing only
        // needs the product row to exist for this assertion, but the stock row is written anyway
        // so the smuggled product behaves like any other once the cache does notice it.
        jdbc.update(
                "INSERT INTO product (name, description, price, category, version) "
                        + "VALUES ('Smuggled In', 'inserted with SQL', 1.00, 'CACHE', 0)");

        try {
            assertThat(rest.getForObject("/api/products", ProductResponse[].class))
                    .as("the cached listing has not noticed the new row")
                    .hasSize(before);

            clearCaches();
            assertThat(rest.getForObject("/api/products", ProductResponse[].class))
                    .as("after eviction it has")
                    .hasSize(before + 1);
        } finally {
            jdbc.update("DELETE FROM product WHERE name = 'Smuggled In'");
        }
    }

    @Test
    @DisplayName("an update refreshes the entry rather than just dropping it")
    void updateWritesThroughToTheCache() {
        ProductResponse product = create("Cached Desk", "499.00", 3);
        rest.getForObject("/api/products/" + product.id(), ProductResponse.class);

        admin.put(
                "/api/products/" + product.id(),
                new ProductRequest("Cached Desk v2", "Adjustable", new BigDecimal("549.00"), 4, "CACHE"));

        // @CachePut wrote the new value straight in, so this is a HIT carrying the new price —
        // not a miss that happens to re-read the right thing. Proved by moving the database
        // again: if the entry were merely evicted, this read would return 1.00.
        changePriceBehindTheCache(product.id(), "1.00");
        ProductResponse afterUpdate =
                rest.getForObject("/api/products/" + product.id(), ProductResponse.class);
        assertThat(afterUpdate.price())
                .as("the cache holds the value the update returned")
                .isEqualByComparingTo("549.00");
        assertThat(afterUpdate.name()).isEqualTo("Cached Desk v2");
    }

    @Test
    @DisplayName("a delete evicts the entry, so the next read is a genuine 404")
    void deleteEvictsTheEntry() {
        ProductResponse product = create("Cached Doomed", "20.00", 1);
        rest.getForObject("/api/products/" + product.id(), ProductResponse.class);

        admin.delete("/api/products/" + product.id());
        createdIds.remove(product.id());

        assertThat(rest.getForEntity("/api/products/" + product.id(), String.class).getStatusCode())
                .as("a stale cache entry here would keep serving a product that no longer exists")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("entries are readable JSON under a readable key")
    void theCacheIsLegible() {
        ProductResponse product = create("Cached Readable", "42.50", 7);
        rest.getForObject("/api/products/" + product.id(), ProductResponse.class);

        String key = CacheNames.PRODUCT + "::" + product.id();
        assertThat(redis.hasKey(key))
                .as("a human can find this key with `redis-cli KEYS 'product::*'`")
                .isTrue();

        String json = redis.opsForValue().get(key);
        assertThat(json)
                .as("JSON, not a Java-serialized blob")
                .contains("Cached Readable")
                .contains("42.50");

        // The TTL is the backstop for every eviction nobody wrote. Without it a single missed
        // @CacheEvict would be wrong for ever.
        assertThat(redis.getExpire(key)).as("the entry expires on its own").isPositive();
    }

    @Test
    @DisplayName("money survives the JSON round trip with its scale intact")
    void bigDecimalKeepsItsScale() {
        // BigDecimal is the reason to care which serializer is used. 42.50 and 42.5 are equal as
        // numbers and different as BigDecimals, and a serializer that goes through a double would
        // turn 0.1 + 0.2 money into a support ticket.
        ProductResponse product = create("Cached Money", "42.50", 1);

        rest.getForObject("/api/products/" + product.id(), ProductResponse.class);
        ProductResponse fromCache =
                rest.getForObject("/api/products/" + product.id(), ProductResponse.class);

        assertThat(fromCache.price()).isEqualByComparingTo("42.50");
        assertThat(fromCache.price().scale()).as("two decimal places, as stored").isEqualTo(2);
        assertThat(fromCache.price()).isEqualTo(new BigDecimal("42.50"));
    }

    // ------------------------------------------------------------------------------------------
    // THREE TESTS LEFT THIS CLASS IN PHASE 20c, and where they went matters
    // ------------------------------------------------------------------------------------------
    // They asserted things that are no longer assertable from inside ONE deployable, because each
    // needed a cart, an order and a stock reservation, and those now live in two other services:
    //
    //   "CHECKOUT NEVER READS THE CACHE"                -> smoke test, "Caching" section
    //   "after a sale the catalogue is stale, then correct" -> smoke test, the convergence check
    //   "the oversell guard still holds with the cache warm" -> smoke test, the race probe
    //
    // They were NOT dropped to make this module compile. Each claim was checked against the smoke
    // test BEFORE the move, and two of the three were not in fact covered there: the smoke test
    // warmed no cache before its oversell probe, and nothing asserted that checkout reads live
    // stock. Those checks were ADDED to the smoke test in the same commit that removed these
    // methods, which is the only thing that makes this a relocation rather than a deletion.
    //
    // This is the honest cost of the split: a claim that spans three services can only be made
    // where all three are running, and that is the smoke test, not an integration test.
}

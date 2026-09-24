package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
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
class CacheApiIT extends IntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.ecomdemo.catalog.ProductService productService;

    /**
     * Since Phase 20 the stock-changed event is published by the INVENTORY module, with the column
     * it describes. These two tests care only that the AFTER_COMMIT listener fires on a commit and
     * not on a rollback, so what matters is that something publishes the event inside a
     * transaction - a reservation now does.
     */
    @Autowired
    private com.ecomdemo.inventory.InventoryService inventoryService;

    @Autowired
    private TransactionTemplate transactions;

    private final List<Long> createdIds = new ArrayList<>();

    private TestRestTemplate admin;

    private TestRestTemplate shopper;

    @BeforeEach
    void signInAndStartFromAnEmptyCacheAndCart() {
        admin = asAdmin();
        shopper = asCustomer("it-cache-shopper");
        // The account persists for the life of the container, so its cart carries whatever the
        // previous test left in it. A leftover line makes the next checkout fail with 409 for a
        // reason that has nothing to do with caching.
        emptyTheCart();
        clearCaches();
    }

    private void emptyTheCart() {
        CartResponse cart = shopper.getForObject("/api/cart", CartResponse.class);
        if (cart != null) {
            cart.items().stream()
                    .map(CartItemResponse::productId)
                    .forEach(id -> shopper.delete("/api/cart/items/" + id));
        }
    }

    @AfterEach
    void cleanUp() {
        emptyTheCart();
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
        jdbc.update(
                "INSERT INTO product_stock (product_id, quantity, version) "
                        + "SELECT id, 1, 0 FROM product WHERE name = 'Smuggled In'");
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

    @Test
    @DisplayName("CHECKOUT NEVER READS THE CACHE — the stock it decides on is always live")
    void checkoutIgnoresTheCache() {
        // This is the test the whole phase turns on. Caching the catalogue is harmless; caching
        // the number a sale is decided against would resurrect the Phase 6 oversell bug in a form
        // optimistic locking cannot catch, because the version it compares would itself be stale.
        ProductResponse product = create("Cached Stock", "5.00", 3);

        // Warm the cache, then move the real stock underneath it.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class)
                        .stockQuantity())
                .isEqualTo(3);
        jdbc.update("UPDATE product_stock SET quantity = 1 WHERE product_id = ?", product.id());

        // The catalogue is now knowingly stale — that is the accepted trade.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class)
                        .stockQuantity())
                .as("the browsing view still shows the cached figure")
                .isEqualTo(3);

        // Buy 1 of the 1 that really exists. Checkout reads through requireProduct(), which is
        // not cached, so it must see 1 and succeed...
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(product.id(), 1), String.class);
        assertThat(shopper.postForEntity("/api/orders", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // ...and the database must now hold 0, not 2. A cached read of "3" would have left 2.
        Integer stock = jdbc.queryForObject(
                "SELECT quantity FROM product_stock WHERE product_id = ?", Integer.class,
                product.id());
        assertThat(stock)
                .as("checkout decremented the LIVE value (1 -> 0), not the cached one (3 -> 2)")
                .isZero();
    }

    @Test
    @DisplayName("a committed checkout leaves no stale stock behind, in either cache")
    void aCommittedCheckoutEvictsTheCatalogue() {
        // The bug this closes: before the after-commit eviction existed, a sale moved the
        // database and left both caches advertising the pre-sale figure — for ten minutes on the
        // product and two on the listing. A shopper could read "5 in stock", add five to a cart
        // and be refused at checkout, with the database correct and unhelpful throughout.
        ProductResponse product = create("Evicted After Sale", "250.00", 5);

        // Warm BOTH caches, so there is something stale to find.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class)
                        .stockQuantity())
                .isEqualTo(5);
        assertThat(listedStockOf(product.id())).isEqualTo(5);
        assertThat(redis.keys("product::" + product.id())).isNotEmpty();

        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(product.id(), 2), String.class);
        assertThat(shopper.postForEntity("/api/orders", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // No sleep and no TTL: the eviction happened as part of finishing the checkout, so the
        // very next read is already correct.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class)
                        .stockQuantity())
                .as("the product page must not still be advertising stock that was just sold")
                .isEqualTo(3);
        assertThat(listedStockOf(product.id()))
                .as("and neither must the catalogue listing, which is one cache entry for all products")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the eviction is tied to the COMMIT: a rolled-back transaction evicts nothing")
    void aRolledBackTransactionEvictsNothing() {
        // This is the half an end-to-end test cannot show, and the reason the eviction is a
        // transactional listener rather than an @CacheEvict on the write: evicting during the
        // transaction throws away a good entry every time a checkout fails. Verified by mutation
        // — putting @CacheEvict back on save() fails exactly this test.
        //
        // Note what it does NOT prove, because claiming otherwise would be worth less than
        // saying so: it does not distinguish AFTER_COMMIT from BEFORE_COMMIT. Spring skips
        // before-commit callbacks entirely on a rollback-only transaction, so both phases behave
        // identically here. The reason the listener uses AFTER_COMMIT is the other hazard — a
        // concurrent reader repopulating the cache from the not-yet-committed row — and that is
        // a race with no deterministic hook to test against.
        ProductResponse product = create("Rolled Back", "80.00", 7);
        rest.getForObject("/api/products/" + product.id(), ProductResponse.class);

        // Make the cached entry provably distinguishable from the database.
        changePriceBehindTheCache(product.id(), "4242.00");

        transactions.execute(status -> {
            inventoryService.reserve(product.id(), "cache test", 1);
            status.setRollbackOnly();
            return null;
        });

        // The event was published inside that transaction and must never have been delivered.
        // 80.00 is the price the cache was warmed with; the database now says 4242.00. Reading
        // the OLD value back proves the entry survived the rollback untouched.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class).price())
                .as("a rolled-back write must leave the cache exactly as it found it")
                .isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("a committed transaction does evict, so the two cases differ only in the commit")
    void aCommittedTransactionEvicts() {
        ProductResponse product = create("Committed", "80.00", 7);
        rest.getForObject("/api/products/" + product.id(), ProductResponse.class);
        changePriceBehindTheCache(product.id(), "4242.00");

        transactions.execute(status -> {
            inventoryService.reserve(product.id(), "cache test", 1);
            return null;
        });

        // Same code, same event, one difference: this transaction committed. The entry is gone,
        // so the next read goes to the database and sees the price written behind the cache.
        assertThat(rest.getForObject("/api/products/" + product.id(), ProductResponse.class).price())
                .as("a committed write must drop the stale entry")
                .isEqualByComparingTo("4242.00");
    }

    @Test
    @DisplayName("a second checkout of the last unit is still refused, with the cache warm")
    void theOversellGuardStillHoldsWithACacheInFront() {
        ProductResponse product = create("Cached Last One", "99.00", 1);
        rest.getForObject("/api/products/" + product.id(), ProductResponse.class);

        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(product.id(), 1), String.class);
        assertThat(shopper.postForEntity("/api/orders", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // The one unit is gone. With a cached stock figure the second attempt would be allowed
        // through and oversell; reading live, it is refused.
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(product.id(), 1), String.class);
        assertThat(shopper.postForEntity("/api/orders", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}

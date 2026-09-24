package com.ecomdemo.catalog;

import com.ecomdemo.catalog.internal.ProductRepository;
import com.ecomdemo.inventory.InventoryService;
import com.ecomdemo.cache.CacheNames;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules for the catalogue. The controller does HTTP, the repository does SQL,
 * and everything in between lives here.
 *
 * <p>The class defaults to {@code readOnly = true} and the four writing methods override it.
 * Declaring it this way round means a new read method is safe by default and a new write method
 * fails loudly if its author forgets to say so, rather than the other way round.
 *
 * <p>A note on what {@code readOnly} means here, because it is easy to over-read. It is a hint,
 * not a guarantee: Hibernate skips the dirty-check snapshot of every loaded entity and sets
 * manual flush mode, and the JDBC connection is marked read-only for drivers that care. It also
 * only applies when this bean actually starts the transaction. {@link #requireProduct(Long)} is
 * called from inside the cart's and the order's read-write transactions, and propagation
 * {@code REQUIRED} joins theirs — the outer transaction's settings win, which is precisely why
 * the product they load can still be modified and saved.
 *
 * <h2>What is cached, and what must never be</h2>
 *
 * <p>Only {@link #findAll()} and {@link #findById(Long)} are cached. Both return
 * {@code ProductResponse} — a DTO, a snapshot, safe to hold.
 *
 * <p>{@link #requireProduct(Long)} is deliberately <strong>not</strong> cached, and that is the
 * most important decision in this class. It returns a managed JPA entity and it is what the cart
 * and checkout use. Caching it would hand the checkout a detached object carrying a stale
 * {@code version} and a stale {@code stockQuantity}, which is precisely the lost-update anomaly
 * Phase 6 exists to prevent — except that optimistic locking could not catch it, because the
 * version it compares would itself have come from the cache. The oversell bug would return,
 * silently, and the race test would still pass because two threads would agree on the same wrong
 * number.
 *
 * <p>Put plainly: <strong>cache what is read often and changes rarely; never cache what a
 * decision is made against.</strong> Stock is the second kind.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    /**
     * How this service tells the rest of the application that something happened, without knowing
     * who is listening. Today the only listener is {@code ProductCacheEvictor}; the alternative —
     * injecting a {@code CacheManager} here — would put cache mechanics inside the catalogue's
     * domain logic and make this class harder to test for reasons having nothing to do with
     * products.
     */

    /**
     * Constructor injection: the dependency is final, the object cannot exist in a half-built
     * state, and the class can be instantiated in a plain unit test with {@code new}.
     */
    private final InventoryService inventory;

    public ProductService(ProductRepository productRepository,
            InventoryService inventory) {
        this.inventory = inventory;
        this.productRepository = productRepository;
    }

    /**
     * The whole catalogue, under one key.
     *
     * <p>{@code key = "'all'"} is a SpEL literal, not a field: the method takes no arguments, so
     * without it Spring uses {@code SimpleKey.EMPTY} — which works, but shows up in Redis as an
     * opaque key nobody can recognise. {@code productList::all} is greppable.
     */
    @Cacheable(cacheNames = CacheNames.PRODUCT_LIST, key = "'all'")
    public List<ProductResponse> findAll() {
        List<Product> products = productRepository.findAll();

        // ONE stock lookup for the whole listing, not one per product. This is the N+1 problem
        // that the JOIN FETCH queries elsewhere in this application exist to avoid - and here it
        // matters more than usual, because when inventory becomes a separate service this line
        // becomes a single HTTP call rather than one per row.
        Map<Long, Integer> quantities =
                inventory.quantitiesFor(products.stream().map(Product::getId).toList());

        return products.stream()
                .map(product -> ProductResponse.from(
                        product, quantities.getOrDefault(product.getId(), 0)))
                .toList();
    }

    /**
     * One product, by id.
     *
     * <p>Note this caches the DTO rather than the entity: what goes into Redis is the JSON of a
     * {@code ProductResponse}, which has no Hibernate proxies, no lazy associations and no
     * identity to be confused with a managed instance.
     */
    @Cacheable(cacheNames = CacheNames.PRODUCT, key = "#id")
    public ProductResponse findById(Long id) {
        Product product = requireProduct(id);
        return ProductResponse.from(product, inventory.quantityFor(id));
    }

    /**
     * A new product invalidates the listing, and nothing else — there is no entry for an id that
     * did not exist a moment ago.
     */
    @CacheEvict(cacheNames = CacheNames.PRODUCT_LIST, key = "'all'")
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(
                request.name(), request.description(), request.price(), request.category());
        Product saved = productRepository.save(product);

        // The stock row is created here, by the code that creates the product, because there is
        // no foreign key to do it - see the V11 migration for why adding one would be a liability
        // rather than a guarantee. Inside one transaction today; two calls across a service
        // boundary shortly, which is exactly the distributed write this phase has to face.
        inventory.setStockLevel(saved.getId(), request.stockQuantity());

        return ProductResponse.from(saved, request.stockQuantity());
    }

    /**
     * An update both refreshes and invalidates, which is why it needs {@code @Caching} to say two
     * things at once.
     *
     * <p>{@code @CachePut} on the single entry writes the new value straight into the cache
     * rather than removing it — the method has to run anyway, and its return value is exactly
     * what a subsequent read would produce, so evicting and re-reading would be a wasted database
     * round trip. {@code @CacheEvict} on the listing does the opposite, because the listing holds
     * every product and there is no way to patch one entry inside it.
     *
     * <p>That asymmetry is the whole of cache invalidation in miniature: refresh what you can
     * compute, discard what you cannot.
     */
    @Caching(
            put = @CachePut(cacheNames = CacheNames.PRODUCT, key = "#id"),
            evict = @CacheEvict(cacheNames = CacheNames.PRODUCT_LIST, key = "'all'"))
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = requireProduct(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setCategory(request.category());
        Product saved = productRepository.save(product);

        inventory.setStockLevel(id, request.stockQuantity());

        return ProductResponse.from(saved, request.stockQuantity());
    }

    /** A delete has to remove both: the entry for this id, and the listing that contained it. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.PRODUCT_LIST, key = "'all'")})
    @Transactional
    public void delete(Long id) {
        productRepository.delete(requireProduct(id));
        inventory.forget(id);
    }

    /**
     * Shared lookup so every caller produces the same 404 message.
     *
     * <p><strong>Never cached.</strong> See the class javadoc: this returns a managed entity, and
     * the cart and checkout make decisions against its {@code stockQuantity} and {@code version}.
     * A cached copy would defeat optimistic locking rather than merely be stale.
     */
    public Product requireProduct(Long id) {
        return productRepository.findById(id).orElseThrow(() -> NotFoundException.product(id));
    }

    /**
     * Used by the order feature once stock has been reduced.
     *
     * <p>Always called from inside the checkout transaction, which it joins. The versioned
     * UPDATE that this eventually produces is not issued here — it is issued when that
     * transaction flushes, which is where an optimistic lock failure surfaces.
     *
     * <p><strong>It still evicts nothing itself</strong>, and the Phase 13 reasoning for that is
     * unchanged: this runs once per line inside a transaction that may still roll back, so an
     * {@code @CacheEvict} here would discard good entries on every failed checkout and — worse —
     * evict before the commit, leaving a window in which a concurrent read repopulates the cache
     * from the pre-commit row and is wrong until the TTL.
     *
     * <p><strong>What has changed is that the eviction now happens afterwards.</strong> Phase 13
     * accepted a stale catalogue for up to a TTL after every sale, and recorded transaction
     * synchronisation as the real answer, deferred. This is that answer: the method publishes
     * {@link ProductStockChangedEvent}, and {@code ProductCacheEvictor} acts on it in
     * {@code AFTER_COMMIT} — so a rolled-back checkout still evicts nothing, and a committed one
     * leaves no stale entry behind.
     *
     * <p>The event is published inside the transaction and delivered outside it; that is what
     * {@code @TransactionalEventListener} does, and publishing here rather than in
     * {@code OrderPlacementService} keeps the ordering code free of any knowledge that a cache
     * exists.
     */
    /**
     * The catalogue row with this name, oldest first, or empty.
     *
     * <p>Added in Phase 19 so that {@code ProductRepository} could move into {@code internal}.
     * The CSV import needs an upsert-by-name — it treats the product name as the natural key of a
     * supplier feed — and was calling the repository directly across a module boundary to get it.
     *
     * <p>"Oldest first" is not arbitrary and is not this method's idea: the API has allowed two
     * products to share a name since Phase 1, so the import updates the FIRST match rather than
     * failing or guessing. That rule now lives here, on the catalogue's own API, instead of being
     * encoded in the repository method name that every caller had to know to pick.
     */
    @Transactional(readOnly = true)
    public Optional<Product> findFirstByName(String name) {
        return productRepository.findFirstByNameOrderByIdAsc(name);
    }

    /**
     * Saves many products at once, for the batch import.
     *
     * <p>Bulk rather than one-by-one, and deliberately without any cache eviction of its own: the
     * import changes the catalogue wholesale and {@code ProductUpsertWriter} evicts once at the
     * end of the step. Firing an eviction per row would be thousands of them to invalidate a cache
     * that the import's scale has already made useless.
     */
    @Transactional
    public void saveAll(Iterable<? extends Product> products) {
        productRepository.saveAll(products);
    }
}

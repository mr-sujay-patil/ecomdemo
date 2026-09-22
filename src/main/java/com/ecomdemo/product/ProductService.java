package com.ecomdemo.product;

import com.ecomdemo.cache.CacheNames;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import java.util.List;
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
     * Constructor injection: the dependency is final, the object cannot exist in a half-built
     * state, and the class can be instantiated in a plain unit test with {@code new}.
     */
    public ProductService(ProductRepository productRepository) {
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
        return productRepository.findAll().stream().map(ProductResponse::from).toList();
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
        return ProductResponse.from(requireProduct(id));
    }

    /**
     * A new product invalidates the listing, and nothing else — there is no entry for an id that
     * did not exist a moment ago.
     */
    @CacheEvict(cacheNames = CacheNames.PRODUCT_LIST, key = "'all'")
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(
                request.name(), request.description(), request.price(), request.stockQuantity(),
                request.category());
        return ProductResponse.from(productRepository.save(product));
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
        product.setStockQuantity(request.stockQuantity());
        product.setCategory(request.category());
        return ProductResponse.from(productRepository.save(product));
    }

    /** A delete has to remove both: the entry for this id, and the listing that contained it. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.PRODUCT, key = "#id"),
            @CacheEvict(cacheNames = CacheNames.PRODUCT_LIST, key = "'all'")})
    @Transactional
    public void delete(Long id) {
        productRepository.delete(requireProduct(id));
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
     */
    /**
     * <p><strong>Deliberately does not evict anything.</strong> This is the checkout's path: it is
     * called once per line, inside a transaction that may still roll back, and evicting there
     * would throw away good entries on every failed checkout — and, worse, evict before the
     * commit, so a concurrent read could repopulate the cache from the pre-commit state and be
     * wrong until the TTL expires.
     *
     * <p>What that costs is honest: after a sale, a cached product shows the old stock figure for
     * up to the cache's TTL. That is the trade this phase is actually about. The catalogue is a
     * browsing view and a slightly stale count there misleads nobody — checkout reads through
     * {@link #requireProduct(Long)}, which never touches the cache, so the number that decides
     * whether a sale happens is always live.
     */
    @Transactional
    public void save(Product product) {
        productRepository.save(product);
    }
}

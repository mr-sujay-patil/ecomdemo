package com.ecomdemo.support;

import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.ProductUpsert;
import com.ecomdemo.clients.catalog.ProductWrite;
import com.ecomdemo.clients.inventory.InventoryGateway;
import com.ecomdemo.shared.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A FAKE catalogue, not a mock — the same judgement {@link InMemoryInventory} records.
 *
 * <p>A mock would have to be told, in every test that touches a cart, what the catalogue returns
 * for each id. That is setup nobody reads, it drifts from the real behaviour, and it turns "add a
 * product to a cart" into a scripted conversation. This remembers products instead, so a test can
 * create one and then use it, which is what the test is actually about.
 *
 * <p>It is the seam the split bought: the application's tests never stand up catalog-service, and
 * they do not have to, because what they assert is the application's behaviour given a catalogue —
 * not the catalogue's behaviour. That is asserted in catalog-service, against its own database.
 */
public class InMemoryCatalog implements CatalogGateway {

    private final Map<Long, ProductSnapshot> products = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /**
     * The fake inventory, because the REAL catalog-service talks to the real inventory-service on
     * every write — {@code create} and {@code update} call {@code setStockLevel}, {@code delete}
     * calls {@code forget}.
     *
     * <p>Leaving that out is what made four integration tests fail with a 409 on checkout: a
     * product created through this fake existed in the catalogue and had no stock anywhere, so
     * every attempt to buy it was correctly refused. The fake was lying by omission, which is the
     * characteristic failure of fakes and the reason this one now mirrors the collaboration
     * instead of just the data.
     */
    private final InventoryGateway inventory;

    public InMemoryCatalog(InventoryGateway inventory) {
        this.inventory = inventory;
    }

    @Override
    public List<ProductSnapshot> findAll() {
        // Stock is overlaid from the inventory fake on every READ, because that is what
        // catalog-service does: its ProductResponse.from(product, inventory.quantityFor(id)) asks
        // inventory-service each time rather than remembering a number.
        //
        // Returning the figure stored at creation instead is what made `placeOrderEndToEnd` read 5
        // after selling 2 of 5. The catalogue does not own that number and must not cache it in a
        // field; a fake that does is asserting something the real thing never promised.
        return products.values().stream().map(this::withLiveStock).toList();
    }

    @Override
    public ProductSnapshot create(ProductWrite product) {
        long id = nextId.getAndIncrement();
        ProductSnapshot snapshot = new ProductSnapshot(id, product.name(), product.description(),
                product.price(), product.category(), product.stockQuantity());
        products.put(id, snapshot);
        setStock(id, product.stockQuantity());
        return snapshot;
    }

    @Override
    public ProductSnapshot update(Long productId, ProductWrite product) {
        requireProduct(productId);
        ProductSnapshot snapshot = new ProductSnapshot(productId, product.name(), product.description(),
                product.price(), product.category(), product.stockQuantity());
        products.put(productId, snapshot);
        setStock(productId, product.stockQuantity());
        return snapshot;
    }

    @Override
    public void delete(Long productId) {
        requireProduct(productId);
        products.remove(productId);
        inventory.forget(productId);
    }

    private void setStock(Long productId, Integer quantity) {
        if (quantity != null) {
            inventory.setStockLevel(productId, quantity);
        }
    }

    @Override
    public ProductSnapshot requireProduct(Long productId) {
        ProductSnapshot product = products.get(productId);
        if (product == null) {
            // The real client maps catalog-service's 404 to exactly this, so a test that expects a
            // missing product to surface as a 404 is testing the same path it would in production.
            throw NotFoundException.product(productId);
        }
        return withLiveStock(product);
    }

    /** The product as the catalogue would return it: its own fields, inventory's number. */
    private ProductSnapshot withLiveStock(ProductSnapshot product) {
        return new ProductSnapshot(product.id(), product.name(), product.description(),
                product.price(), product.category(), inventory.quantityFor(product.id()));
    }

    @Override
    public List<ProductSnapshot> upsertAll(List<ProductUpsert> upserts) {
        List<ProductSnapshot> saved = new ArrayList<>();
        for (ProductUpsert upsert : upserts) {
            Long id = upsert.id() != null ? upsert.id() : idForName(upsert.name());
            ProductSnapshot snapshot = new ProductSnapshot(
                    id, upsert.name(), upsert.description(), upsert.price(), upsert.category(), null);
            products.put(id, snapshot);
            saved.add(snapshot);
        }
        return saved;
    }

    /**
     * Resolves a create by NAME, because that is what catalog-service does with a null id — and a
     * fake that assigned a fresh id every time would make the CSV import look non-idempotent when
     * it is not.
     */
    private Long idForName(String name) {
        return products.values().stream()
                .filter(p -> p.name().equals(name))
                .map(ProductSnapshot::id)
                .min(Long::compareTo)
                .orElseGet(nextId::getAndIncrement);
    }

    /** Puts a product in, for a test that needs one to exist without going through an import. */
    public ProductSnapshot given(String name, String description, java.math.BigDecimal price, String category) {
        long id = nextId.getAndIncrement();
        ProductSnapshot snapshot = new ProductSnapshot(id, name, description, price, category, 0);
        products.put(id, snapshot);
        return snapshot;
    }

    public void clear() {
        products.clear();
    }
}

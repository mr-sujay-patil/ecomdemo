package com.ecomdemo.inventory;

import com.ecomdemo.shared.InsufficientStockException;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many of a product there are, and the only route by which that number changes.
 *
 * <h2>What changed in Phase 20, and why it matters more than it looks</h2>
 *
 * <p>Phase 19 created this module but left it holding a {@code Product}: stock was a column on the
 * catalogue's entity, so changing it meant mutating a catalogue object and saving through
 * {@code ProductService}. That was an edge from inventory to catalog, and it was the reason the
 * ArchUnit rule guarding stock had to permit two modules rather than one.
 *
 * <p>Now stock is its own table and this class deals in product <strong>ids</strong>. It never
 * sees a {@code Product}, cannot reach the catalogue, and does not know what one is. The edge is
 * gone, and the only remaining one runs the other way — the catalogue asking here for a number it
 * needs to answer an API call.
 *
 * <p>That inversion is not tidiness. It is the shape the services have to have: an
 * inventory-service will not have a catalogue to depend on, and an id is exactly what it will
 * receive over HTTP. The split made the code admit what the deployment was always going to
 * require.
 *
 * <h2>A missing row means zero, not an error</h2>
 *
 * <p>There is no foreign key from {@code product_stock} to {@code product} — see the V11 migration
 * for why one would be a liability rather than a guarantee. So this class cannot assume a row
 * exists, and treats absence as zero rather than throwing. A product with no stock row is a
 * product with nothing in stock, which is both true and the answer a caller can act on.
 */
@Service
public class InventoryService {

    private final ProductStockRepository stock;
    private final StockChangePublisher stockChanges;

    public InventoryService(ProductStockRepository stock, StockChangePublisher stockChanges) {
        this.stock = stock;
        this.stockChanges = stockChanges;
    }

    /** How many of one product are available. Zero if it has no stock row. */
    @Transactional(readOnly = true)
    public int quantityFor(Long productId) {
        return stock.findById(productId).map(ProductStock::getQuantity).orElse(0);
    }

    /**
     * Stock for many products at once, keyed by product id.
     *
     * <p>One query, not one per product. The catalogue listing needs a quantity for every row it
     * returns, and asking individually would be the N+1 problem — which becomes N+1 <em>network
     * round trips</em> the moment this is an HTTP call between two services. Shaping the caller
     * around a batch now, while it is still a method call, is what stops that being a rewrite
     * later.
     *
     * <p>Products with no stock row are simply absent from the map; callers default them to zero.
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> quantitiesFor(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return stock.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(ProductStock::getProductId, ProductStock::getQuantity));
    }

    /**
     * Checks that a product can cover a quantity, and says so in the customer's terms if not.
     *
     * <p>Read-only, and separate from {@link #reserve} on purpose: checkout validates every line
     * before writing any of them, so that a cart of five items fails with "3 requested, 2
     * available" for the line that is actually short rather than for whichever line happened to be
     * written when the transaction gave up.
     *
     * <p>It takes the product NAME as well as the id, because the exception a shopper sees names
     * the product and this module no longer has any way to look one up. That is the first small
     * tax of the split, and it is the honest one to pay: the alternative is an inventory service
     * that calls the catalogue back to build an error message.
     */
    @Transactional(readOnly = true)
    public void requireAvailable(Long productId, String productName, int quantity) {
        int available = quantityFor(productId);
        if (available < quantity) {
            throw new InsufficientStockException(productName, quantity, available);
        }
    }

    /**
     * Takes {@code quantity} out of stock.
     *
     * <h2>{@code MANDATORY} is gone, and that is the cost of the boundary</h2>
     *
     * <p>Until Phase 20b this was {@code Propagation.MANDATORY}, so that a reservation could only
     * happen inside the transaction that created the order — stock could not commit while the
     * order that justified it rolled back. **That guarantee cannot survive an HTTP call.** The
     * caller is in another process with another transaction manager; by the time it decides to
     * roll back, this transaction has long since committed.
     *
     * <p>What replaces it is a saga: the caller compensates by calling {@link #release}. That is
     * genuinely weaker, and the weakness is worth naming rather than burying — a crash between
     * this commit and the caller's compensation leaks stock, and nothing here will notice.
     * Reconciling that is a later phase's job, not this one's. What a distributed system buys in
     * independence it pays for in guarantees, and this method is where the bill arrives.
     *
     * <p>The availability check is repeated here even though {@link #requireAvailable} has usually
     * just run, so that this method is safe for any caller rather than only for one that
     * remembered the protocol.
     *
     * <p>Publishing the stock change is what keeps a catalogue cache correct. It was a Spring
     * application event while everything was one process and is a Kafka message now, for the
     * obvious reason that an in-process event cannot reach another process — see
     * {@link StockChangePublisher}.
     */
    @Transactional
    public void reserve(Long productId, String productName, int quantity) {
        ProductStock row = stock.findById(productId)
                .orElseThrow(() -> new InsufficientStockException(productName, quantity, 0));

        if (!row.has(quantity)) {
            throw new InsufficientStockException(productName, quantity, row.getQuantity());
        }

        row.reduce(quantity);
        stock.save(row);
        stockChanges.publish(productId);
    }

    /**
     * Sets an absolute stock level, creating the row if the product has never had one.
     *
     * <p>Two callers: the catalogue, when a product is created or edited, and the CSV import. Both
     * are stating what the level IS rather than moving it, which is why this is not
     * {@link #reserve}.
     *
     * <p>It does not publish {@link ProductStockChangedEvent}. The catalogue's own
     * {@code @CacheEvict} annotations already cover a create or an update, and the import changes
     * the catalogue wholesale — firing one eviction per imported row would be thousands of them to
     * invalidate a cache that the import's scale has already made useless.
     */
    @Transactional
    public void setStockLevel(Long productId, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException(
                    "Stock level cannot be negative: " + quantity + " for product " + productId);
        }
        ProductStock row = stock.findById(productId)
                .orElseGet(() -> new ProductStock(productId, 0));
        row.setQuantity(quantity);
        stock.save(row);
    }

    /**
     * Puts stock back, compensating for a reservation whose order did not survive.
     *
     * <p><strong>This method exists only because {@link #reserve} lost its transaction.</strong>
     * In one process the reservation simply rolled back with everything else and there was nothing
     * to undo. Across a boundary the undo has to be an action, which means it can be forgotten,
     * can fail, and can arrive late — a compensating transaction is not a rollback, it is a second
     * business operation that happens to mean the opposite of the first.
     *
     * <p>It is deliberately NOT symmetric with reserve. Releasing stock for a product with no row
     * creates one: the row may have been swept between the reservation and the compensation, and
     * refusing to give the units back would turn a recoverable situation into permanently lost
     * stock. Releasing more than was taken is not checked either, for the same reason — this is
     * the recovery path, and a recovery path that can itself fail is worse than one that is
     * generous.
     */
    @Transactional
    public void release(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Cannot release a non-positive quantity: " + quantity);
        }
        ProductStock row = stock.findById(productId).orElseGet(() -> new ProductStock(productId, 0));
        row.setQuantity(row.getQuantity() + quantity);
        stock.save(row);
        stockChanges.publish(productId);
    }

    /** Forgets a product's stock entirely, for when the catalogue deletes the product. */
    @Transactional
    public void forget(Long productId) {
        stock.deleteById(productId);
    }

    /** Helper for callers holding a collection of ids that may include ones with no row. */
    public static Function<Long, Integer> orZero(Map<Long, Integer> quantities) {
        return id -> quantities.getOrDefault(id, 0);
    }
}

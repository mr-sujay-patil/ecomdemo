package com.ecomdemo.inventory;

import com.ecomdemo.shared.InsufficientStockException;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    public InventoryService(ProductStockRepository stock, ApplicationEventPublisher events) {
        this.stock = stock;
        this.events = events;
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
     * <p><strong>{@code MANDATORY}, for the reason Phase 18's {@code OutboxWriter} uses it.</strong>
     * A reservation is only meaningful as part of the transaction that creates the order. If this
     * were allowed to open one of its own, stock could commit while the order that justified it
     * rolled back — goods sold to nobody, and no error anywhere. {@code REQUIRED}, the default,
     * would do exactly that silently.
     *
     * <p>The availability check is repeated here even though {@link #requireAvailable} has usually
     * just run, so that this method is safe for any caller rather than only for one that
     * remembered the protocol.
     *
     * <p>Publishing {@link ProductStockChangedEvent} is what keeps the catalogue's cache correct.
     * The event moved here from {@code ProductService} with the column it describes: stock
     * changing is now this module's fact to state, and the Phase 16 evictor listens for it exactly
     * as before.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long productId, String productName, int quantity) {
        ProductStock row = stock.findById(productId)
                .orElseThrow(() -> new InsufficientStockException(productName, quantity, 0));

        if (!row.has(quantity)) {
            throw new InsufficientStockException(productName, quantity, row.getQuantity());
        }

        row.reduce(quantity);
        stock.save(row);
        events.publishEvent(new ProductStockChangedEvent(productId));
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

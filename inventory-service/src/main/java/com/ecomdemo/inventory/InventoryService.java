package com.ecomdemo.inventory;

import com.ecomdemo.shared.InsufficientStockException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Why a reservation was refused for an order the saga deadline had already closed. */
    static final String CLOSED_BEFORE_RESERVATION =
            "The order was closed before its stock could be reserved";

    private final ProductStockRepository stock;
    private final StockReservationRepository reservations;
    private final StockChangePublisher stockChanges;
    private final ClosedOrderRepository closedOrders;
    private final OrderLocks orderLocks;

    public InventoryService(
            ProductStockRepository stock,
            StockReservationRepository reservations,
            StockChangePublisher stockChanges,
            ClosedOrderRepository closedOrders,
            OrderLocks orderLocks) {
        this.stock = stock;
        this.reservations = reservations;
        this.stockChanges = stockChanges;
        this.closedOrders = closedOrders;
        this.orderLocks = orderLocks;
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
     * <p>Read-only, and separate from the reservation on purpose: checkout validates every line
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
     * Sets an absolute stock level, creating the row if the product has never had one.
     *
     * <p>Two callers: the catalogue, when a product is created or edited, and the CSV import. Both
     * are stating what the level IS rather than moving it, which is why this is not a reservation.
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
     * Reserves every line of an order, or none of them. The saga's first local transaction
     * (Phase 24).
     *
     * <p>All or nothing, and that is why it is one method rather than a loop of single-line reservations
     * in the caller: every line is checked against LOCKED rows before any is written, so a cart of
     * five products that is short on the fourth takes nothing at all. A rejection is returned, not
     * thrown - "not enough stock" is one of the saga's two ordinary outcomes, and the caller
     * publishes it as an event. Throwing would roll back the caller's transaction, including the
     * {@code processed_event} marker and the outbox row that announces the rejection.
     *
     * <p>Lines for the same product are added together first, so a cart that somehow listed one
     * product twice is checked against its real total rather than twice against the full stock.
     *
     * <p>Each reserved line is remembered in {@code stock_reservation}: see {@link StockReservation}
     * for why the compensation needs it.
     */
    @Transactional
    public ReservationResult reserveForOrder(Long orderId, List<ReservationLine> lines) {
        // Phase 32: an order the saga deadline has closed gets nothing, however late its
        // OrderCreated arrives. The lock makes "is it closed?" and "reserve" one step with respect
        // to closeOrder - see OrderLocks.
        orderLocks.lock(orderId);
        if (closedOrders.existsById(orderId)) {
            return ReservationResult.rejected(CLOSED_BEFORE_RESERVATION);
        }

        Map<Long, ReservationLine> byProduct = new LinkedHashMap<>();
        for (ReservationLine line : lines) {
            byProduct.merge(
                    line.productId(),
                    line,
                    (a, b) -> new ReservationLine(a.productId(), a.productName(), a.quantity() + b.quantity()));
        }

        Map<Long, ProductStock> rows = stock.lockAllByProductIdIn(byProduct.keySet()).stream()
                .collect(Collectors.toMap(ProductStock::getProductId, Function.identity()));

        for (ReservationLine line : byProduct.values()) {
            ProductStock row = rows.get(line.productId());
            int available = row == null ? 0 : row.getQuantity();
            if (available < line.quantity()) {
                // The same words checkout's pre-check uses, so a shopper whose order is cancelled
                // for stock reads the message they would have got had they been a moment earlier.
                return ReservationResult.rejected(
                        new InsufficientStockException(line.productName(), line.quantity(), available)
                                .getMessage());
            }
        }

        for (ReservationLine line : byProduct.values()) {
            ProductStock row = rows.get(line.productId());
            row.reduce(line.quantity());
            reservations.save(new StockReservation(orderId, line.productId(), line.quantity()));
            stockChanges.publish(line.productId());
        }
        return ReservationResult.reservedAll();
    }

    /**
     * Gives back everything an order still holds. The saga's COMPENSATING transaction, run when
     * payment fails (Phase 24).
     *
     * <p>Idempotent by construction, not only by the caller's {@code processed_event} check: it
     * releases only reservations still {@code RESERVED} and marks each {@code RELEASED} in the
     * same transaction, so a second call finds nothing to give back. Compensation is the one step
     * of a saga that must not fail and must not happen twice, so it gets both guards.
     *
     * @return how many reservations were released; zero for an order that holds nothing
     */
    @Transactional
    public int releaseForOrder(Long orderId) {
        List<StockReservation> held =
                reservations.findByOrderIdAndStatus(orderId, StockReservation.Status.RESERVED);
        if (held.isEmpty()) {
            return 0;
        }

        Set<Long> productIds = held.stream().map(StockReservation::getProductId).collect(Collectors.toSet());
        Map<Long, ProductStock> rows = stock.lockAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(ProductStock::getProductId, Function.identity()));

        for (StockReservation reservation : held) {
            // A row swept since the reservation is recreated, never refused: releasing is the recovery
            // path, and a recovery path that can itself fail is worse than a generous one.
            ProductStock row = rows.computeIfAbsent(
                    reservation.getProductId(), id -> stock.save(new ProductStock(id, 0)));
            row.setQuantity(row.getQuantity() + reservation.getQuantity());
            reservation.markReleased();
            stockChanges.publish(reservation.getProductId());
        }
        return held.size();
    }

    /**
     * Closes an order for good: gives back everything it holds and refuses any reservation for it
     * from now on. The saga deadline's request (Phase 32), made when the order service has decided
     * to cancel an order whose saga never finished.
     *
     * <p>Compensation plus a fence. {@link #releaseForOrder} alone would give back what is held
     * NOW, but the order's OrderCreated may still be on its way - late, or replayed from the
     * dead-letter topic - and would reserve stock again for an order that is already cancelled.
     * The {@code closed_order} row stops that; the order lock stops it racing a reservation that is
     * in progress at this very moment.
     *
     * <p>Idempotent: a second call releases nothing more and reports the order as already closed.
     */
    @Transactional
    public CloseResult closeOrder(Long orderId, String reason) {
        orderLocks.lock(orderId);
        boolean alreadyClosed = closedOrders.existsById(orderId);
        if (!alreadyClosed) {
            closedOrders.save(new ClosedOrder(orderId, reason));
        }
        return new CloseResult(releaseForOrder(orderId), alreadyClosed);
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

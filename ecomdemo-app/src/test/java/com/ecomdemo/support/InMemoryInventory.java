package com.ecomdemo.support;

import com.ecomdemo.inventory.InventoryGateway;
import com.ecomdemo.shared.InsufficientStockException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * inventory-service, standing in for itself, in memory.
 *
 * <h2>A fake, not a mock, and the distinction is the point</h2>
 *
 * <p>Forty-six integration tests create a product and then assert something about carts, orders,
 * caching, metrics or correlation IDs. Not one of them is about the wire to inventory-service —
 * but every one of them now goes through it, because creating a product sets a stock level and
 * checking out reserves.
 *
 * <p>A Mockito mock would make them compile and run, and would quietly turn every
 * {@code stockQuantity} assertion into an assertion that a mock returns zero. This behaves
 * instead: setting a level stores it, reserving reduces it and refuses to oversell with the same
 * {@link InsufficientStockException} a shopper would see, releasing puts it back. The tests keep
 * making the claims they were written to make.
 *
 * <p><strong>What it deliberately cannot prove.</strong> That {@code InventoryClient} speaks the
 * protocol the real service serves — the URLs, the status codes, the JSON. A fake that drifts from
 * the thing it stands in for is worse than no fake at all, so that claim is left to the places
 * that can actually make it: a contract test against a stubbed HTTP server, and the smoke test
 * once Compose runs both. This class is honest about being a stand-in for the BEHAVIOUR and not
 * for the transport.
 */
public class InMemoryInventory implements InventoryGateway {

    private final Map<Long, Integer> quantities = new ConcurrentHashMap<>();

    @Override
    public int quantityFor(Long productId) {
        return quantities.getOrDefault(productId, 0);
    }

    @Override
    public Map<Long, Integer> quantitiesFor(Collection<Long> productIds) {
        return productIds.stream()
                .distinct()
                .filter(quantities::containsKey)
                .collect(Collectors.toMap(id -> id, quantities::get));
    }

    @Override
    public void requireAvailable(Long productId, String productName, int quantity) {
        int available = quantityFor(productId);
        if (available < quantity) {
            throw new InsufficientStockException(productName, quantity, available);
        }
    }

    /**
     * Atomic, because {@code ConcurrentCheckoutTest} runs two threads at this method.
     *
     * <p>It is NOT a stand-in for the optimistic lock that inventory-service holds — that is a
     * database guarantee and is asserted in {@code ConcurrentReservationTest}, against a real
     * PostgreSQL. What this provides is enough consistency that a concurrent test here fails for
     * the reason it is testing rather than for a lost update in the double.
     */
    @Override
    public void reserve(Long productId, String productName, int quantity) {
        quantities.compute(productId, (id, current) -> {
            int available = current == null ? 0 : current;
            if (available < quantity) {
                throw new InsufficientStockException(productName, quantity, available);
            }
            return available - quantity;
        });
    }

    @Override
    public void release(Long productId, int quantity) {
        quantities.merge(productId, quantity, Integer::sum);
    }

    @Override
    public void setStockLevel(Long productId, int quantity) {
        quantities.put(productId, quantity);
    }

    @Override
    public void forget(Long productId) {
        quantities.remove(productId);
    }
}

package com.ecomdemo.clients.inventory;

import java.util.Collection;
import java.util.Map;

/**
 * What this application needs from inventory, independent of how it gets there.
 *
 * <h2>Why an interface, when there is exactly one implementation</h2>
 *
 * <p>Not for future flexibility — that is usually a bad reason and it is not the reason here. It is
 * for the SEAM.
 *
 * <p>Extracting inventory turned five in-process calls into five HTTP calls, and forty-six
 * integration tests that create a product began failing with {@code ConnectException}. Those tests
 * are about carts, orders, caching, metrics and correlation IDs; not one of them is about the wire
 * to inventory-service. Without a seam the only options are to stand up a second application for
 * every one of them, or to mock the client and let every assertion about stock quietly become an
 * assertion about a mock returning zero.
 *
 * <p>This interface gives a third option: a test double that BEHAVES like inventory — create sets a
 * level, reserve reduces it, release puts it back — so those forty-six tests keep making the same
 * claims they always made, about the things they were actually written to check.
 *
 * <p><strong>What the double cannot prove</strong> is that {@link InventoryClient} speaks the
 * protocol inventory-service actually serves. Nothing in this module can prove that, and pretending
 * otherwise would be the real danger of a fake. That claim belongs to a contract test against a
 * stubbed HTTP server, and to the smoke test once Compose runs both.
 */
public interface InventoryGateway {

    int quantityFor(Long productId);

    /** Many products in one call. See the implementation for why this is not a convenience. */
    Map<Long, Integer> quantitiesFor(Collection<Long> productIds);

    void requireAvailable(Long productId, String productName, int quantity);

    void reserve(Long productId, String productName, int quantity);

    /** The compensating half of the checkout saga. */
    void release(Long productId, int quantity);

    void setStockLevel(Long productId, int quantity);

    void forget(Long productId);
}

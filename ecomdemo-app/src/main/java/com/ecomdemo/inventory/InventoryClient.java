package com.ecomdemo.inventory;

import com.ecomdemo.shared.InsufficientStockException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * inventory-service, as the rest of this application still sees it.
 *
 * <h2>Why the method shapes did not change</h2>
 *
 * <p>Every method here matches the {@code InventoryService} it replaces, argument for argument. Not
 * out of sentiment: the five call sites — checkout, the catalogue, the CSV import and its wiring —
 * had already been shaped by Phase 20a into calls that a network can serve. {@code quantitiesFor}
 * takes a collection because a listing must not become N round trips; {@code requireAvailable}
 * takes the product NAME because the far side has no catalogue to look one up in. Those decisions
 * were made while this was still a method call, which is why the extraction changes wiring rather
 * than logic.
 *
 * <h2>What genuinely did change, and cannot be hidden</h2>
 *
 * <p>{@code reserve} used to be {@code Propagation.MANDATORY} inside the caller's transaction, so a
 * checkout that failed afterwards un-reserved automatically. It cannot be now — the reservation
 * commits in another process before this one decides anything. {@link #release} is the
 * compensating half, and {@code OrderPlacementService} is where the compensation is triggered.
 *
 * <p>A wrapper that pretended otherwise would be the worst outcome available: code that reads like
 * a transaction and is not.
 */
@Component
public class InventoryClient implements InventoryGateway {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient rest;

    InventoryClient(RestClient inventoryRestClient) {
        this.rest = inventoryRestClient;
    }

    /** How many of one product are available. Zero if it has no stock row. */
    @Override
    public int quantityFor(Long productId) {
        StockView view = rest.get()
                .uri("/api/inventory/{productId}", productId)
                .retrieve()
                .body(StockView.class);
        return view == null ? 0 : view.quantity();
    }

    /**
     * Stock for many products in ONE call.
     *
     * <p>The reason this exists is now visible rather than theoretical: without it a listing of
     * forty products is forty HTTP requests, where before it was forty queries. The N+1 problem
     * does not disappear at a service boundary, it gets three orders of magnitude more expensive.
     */
    @Override
    public Map<Long, Integer> quantitiesFor(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<StockView> views = rest.get()
                .uri(uri -> uri.path("/api/inventory")
                        .queryParam("productIds", productIds.toArray())
                        .build())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<StockView>>() {});
        return views == null
                ? Map.of()
                : views.stream().collect(Collectors.toMap(StockView::productId, StockView::quantity));
    }

    /** Checks availability, raising the same 409 a shopper saw when this was a method call. */
    @Override
    public void requireAvailable(Long productId, String productName, int quantity) {
        int available = quantityFor(productId);
        if (available < quantity) {
            throw new InsufficientStockException(productName, quantity, available);
        }
    }

    /**
     * Takes stock out.
     *
     * <p>A 409 from the far side is translated back into {@link InsufficientStockException}, so the
     * shopper sees what they always saw. Translating at the boundary rather than letting an HTTP
     * error escape is what keeps the split invisible from the outside — and it is the one place a
     * client like this earns its existence beyond forwarding arguments.
     */
    @Override
    public void reserve(Long productId, String productName, int quantity) {
        rest.post()
                .uri("/api/inventory/{productId}/reserve", productId)
                .body(new UnitsBody(quantity, productName))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new InsufficientStockException(productName, quantity, quantityFor(productId));
                })
                .toBodilessEntity();
    }

    /**
     * Puts stock back, compensating for a reservation whose order did not survive.
     *
     * <p>Failures are logged and swallowed, which is not laziness: this runs on a rollback path,
     * and throwing here would replace the caller's real failure with a second one and lose the
     * first. What it costs is the honest residue of the saga — stock that stays reserved for an
     * order that never existed, until something reconciles it. Nothing does yet, and the test
     * report says so.
     */
    @Override
    public void release(Long productId, int quantity) {
        try {
            rest.post()
                    .uri("/api/inventory/{productId}/release", productId)
                    .body(new UnitsBody(quantity, null))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.error(
                    "Could not release {} unit(s) of product {} after a failed checkout. That stock "
                            + "is now reserved for an order that does not exist and will stay that "
                            + "way until it is reconciled.",
                    quantity, productId, e);
        }
    }

    /** Sets an absolute level: the catalogue on create and update, and the CSV import. */
    @Override
    public void setStockLevel(Long productId, int quantity) {
        rest.put()
                .uri("/api/inventory/{productId}", productId)
                .body(new StockLevelBody(quantity))
                .retrieve()
                .toBodilessEntity();
    }

    /** Forgets a product's stock, for when the catalogue deletes the product. */
    @Override
    public void forget(Long productId) {
        rest.delete()
                .uri("/api/inventory/{productId}", productId)
                .retrieve()
                .toBodilessEntity();
    }

    /** The wire shape, kept package-private: nothing outside this client should know it exists. */
    record StockView(Long productId, int quantity) {}

    /**
     * The two request bodies, one per endpoint shape.
     *
     * <p>They replaced a single three-field record whose call sites read
     * {@code new QuantityBody(null, quantity, null)} — three positional arguments, two of them
     * null, and which two depended on the endpoint. That shape also made the server unable to mark
     * any field required, so a mistake here arrived there as a 500 rather than a 400.
     */
    record UnitsBody(Integer units, String productName) {}

    record StockLevelBody(Integer quantity) {}
}

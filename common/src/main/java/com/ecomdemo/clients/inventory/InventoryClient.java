package com.ecomdemo.clients.inventory;

import com.ecomdemo.shared.InsufficientStockException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 * <p>Taking stock is not here any more. {@code reserve} and {@code release} were an HTTP pair that
 * checkout called and compensated by hand; since Phase 24 the order service publishes
 * {@code OrderCreated} and inventory reserves from the event, inside its own transaction, so the
 * pair was dead code and KI-011 removed it, with the endpoints it called.
 */
@Component
public class InventoryClient implements InventoryGateway {


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

    /** The request body of the one write the client still makes with a body. */
    record StockLevelBody(Integer quantity) {}
}

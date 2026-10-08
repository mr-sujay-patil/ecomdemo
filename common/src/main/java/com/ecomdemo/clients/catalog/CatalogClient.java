package com.ecomdemo.clients.catalog;

import com.ecomdemo.shared.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link CatalogGateway} over HTTP.
 *
 * <p>The one thing it does beyond forwarding arguments is the same thing {@code InventoryClient}
 * does, and it is why a client class earns its existence: it turns catalog-service's {@code 404}
 * back into the {@link NotFoundException} the callers already handle. Without that, adding a
 * missing product to a cart would surface as a raw {@code HttpClientErrorException} and a 500,
 * where it used to be a clean 404 — and the split is not supposed to be visible from outside.
 */
@Component
public class CatalogClient implements CatalogGateway {

    private final RestClient rest;
    private final RestClient bulk;

    // Two clients that differ only in their read timeout; see CatalogProperties. The parameter
    // names are what select the beans - there are several RestClients in the context.
    CatalogClient(RestClient catalogRestClient, RestClient catalogBulkRestClient) {
        this.rest = catalogRestClient;
        this.bulk = catalogBulkRestClient;
    }

    /**
     * Every product, by following the pages (KI-007): {@code GET /api/products} returns one page of at
     * most 100 and says how many there are in all in {@code X-Total-Count}. The contract of this method
     * is still "the whole catalogue", so a silent first page would be a wrong answer, not a short one.
     */
    @Override
    public List<ProductSnapshot> findAll() {
        List<ProductSnapshot> all = new ArrayList<>();
        for (int page = 0;; page++) {
            ResponseEntity<List<ProductSnapshot>> response = rest.get()
                    .uri("/api/products?page={page}&size={size}", page, PAGE_SIZE)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<ProductSnapshot>>() {});
            List<ProductSnapshot> items = response.getBody() == null ? List.of() : response.getBody();
            all.addAll(items);
            if (items.size() < PAGE_SIZE) {
                return all;
            }
        }
    }

    private static final int PAGE_SIZE = 100;

    @Override
    public ProductSnapshot create(ProductWrite product) {
        return rest.post().uri("/api/products").body(product).retrieve().body(ProductSnapshot.class);
    }

    @Override
    public ProductSnapshot update(Long productId, ProductWrite product) {
        return rest.put()
                .uri("/api/products/{id}", productId)
                .body(product)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                        (request, response) -> {
                            throw NotFoundException.product(productId);
                        })
                .body(ProductSnapshot.class);
    }

    @Override
    public void delete(Long productId) {
        rest.delete()
                .uri("/api/products/{id}", productId)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                        (request, response) -> {
                            throw NotFoundException.product(productId);
                        })
                .toBodilessEntity();
    }

    @Override
    public ProductSnapshot requireProduct(Long productId) {
        return rest.get()
                .uri("/api/products/{id}", productId)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                        (request, response) -> {
                            throw NotFoundException.product(productId);
                        })
                .body(ProductSnapshot.class);
    }

    @Override
    public List<ProductSnapshot> upsertAll(List<ProductUpsert> products) {
        return bulk.post()
                .uri("/api/products/batch")
                .body(products)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ProductSnapshot>>() {});
    }
}

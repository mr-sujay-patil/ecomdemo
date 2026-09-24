package com.ecomdemo.clients.catalog;

import com.ecomdemo.shared.NotFoundException;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
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

    CatalogClient(RestClient catalogRestClient) {
        this.rest = catalogRestClient;
    }

    @Override
    public List<ProductSnapshot> findAll() {
        return rest.get()
                .uri("/api/products")
                .retrieve()
                .body(new ParameterizedTypeReference<List<ProductSnapshot>>() {});
    }

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
        return rest.post()
                .uri("/api/products/batch")
                .body(products)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ProductSnapshot>>() {});
    }
}

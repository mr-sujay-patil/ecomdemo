package com.ecomdemo.assistant.store;

import com.ecomdemo.assistant.AssistantProperties;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Everything the assistant asks the rest of the store, and ALWAYS AS THE CUSTOMER.
 *
 * <h2>Why every method takes the caller's token</h2>
 *
 * The assistant could have called catalog-service and the application as itself, with a service
 * token, and filtered the answers down to the customer. That is the classic confused deputy: a
 * component holding more authority than the person it acts for, trusted to use only the part it
 * should. Here the model is part of the deputy, and a model can be talked into things.
 *
 * <p>So there is no service identity at all. The customer's own JWT goes on every request, and the
 * service that OWNS the data applies its own rules: the application answers 403 for somebody else's
 * order, exactly as it would to the customer calling it directly. Whatever the model is persuaded to
 * ask for, it cannot reach further than the customer could with curl.
 */
@Component
public class StoreClient {

    private final RestClient catalog;
    private final RestClient app;

    public StoreClient(RestClient.Builder builder, AssistantProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.catalog = builder.clone().baseUrl(properties.catalogBaseUrl()).requestFactory(requestFactory).build();
        this.app = builder.clone().baseUrl(properties.appBaseUrl()).requestFactory(requestFactory).build();
    }

    /** Catalog's semantic search (Phase 28). */
    public List<ProductView> searchProducts(
            String token, String query, String category, BigDecimal maxPrice, int limit) {
        SearchResponse response = call(() -> catalog.get()
                .uri(uri -> {
                    uri.path("/api/products/search").queryParam("q", query).queryParam("limit", limit);
                    if (category != null && !category.isBlank()) {
                        uri.queryParam("category", category.strip());
                    }
                    if (maxPrice != null) {
                        uri.queryParam("maxPrice", maxPrice.toPlainString());
                    }
                    return uri.build();
                })
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(SearchResponse.class), "Product search");
        return response == null || response.results() == null ? List.of()
                : response.results().stream().map(SearchHit::product).toList();
    }

    /**
     * One of the CALLER's orders. Empty both when it does not exist (404) and when it is somebody
     * else's (403): the model is told the same thing either way, so it cannot even learn which
     * order numbers are in use.
     */
    public Optional<OrderStatusView> orderStatus(String token, long orderId) {
        try {
            return Optional.ofNullable(call(() -> app.get()
                    .uri("/api/orders/{id}/status", orderId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(OrderStatusView.class), "Order lookup"));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                    || e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Called only from a confirmation the customer made, never from a tool. */
    public CartView addToCart(String token, long productId, int quantity) {
        return call(() -> app.post()
                .uri("/api/cart/items")
                .headers(headers -> headers.setBearerAuth(token))
                .body(new AddCartItem(productId, quantity))
                .retrieve()
                .body(CartView.class), "Adding to the cart");
    }

    /**
     * A 4xx is the caller's problem and is passed up as it is; anything else - a 5xx, a timeout, a
     * refused connection - means that part of the store is unavailable right now.
     */
    private static <T> T call(java.util.function.Supplier<T> request, String what) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            throw e;
        } catch (RestClientException e) {
            throw new StoreUnavailableException(what + " is unavailable right now.", e);
        }
    }

    record SearchResponse(String query, List<SearchHit> results) {
    }

    record SearchHit(ProductView product, double similarity) {
    }

    record AddCartItem(Long productId, Integer quantity) {
    }
}

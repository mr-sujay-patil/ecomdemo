package com.ecomdemo.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.ApiError;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The cart over real HTTP and real PostgreSQL.
 *
 * <p>There is still exactly one cart in the system — there are no users until Phase 8 — so it is
 * shared state between these tests and between test classes. Each test empties it first and the
 * probe products are deleted afterwards; that discipline is the price of running against a
 * database that actually commits.
 */
class CartApiIT extends IntegrationTest {

    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void emptyTheCart() {
        cart().items().forEach(item -> rest.delete("/api/cart/items/" + item.productId()));
        assertThat(cart().items()).isEmpty();
    }

    @AfterEach
    void cleanUp() {
        cart().items().forEach(item -> rest.delete("/api/cart/items/" + item.productId()));
        createdIds.forEach(id -> rest.delete("/api/products/" + id));
        createdIds.clear();
    }

    private CartResponse cart() {
        CartResponse body = rest.getForObject("/api/cart", CartResponse.class);
        assertThat(body).isNotNull();
        return body;
    }

    private ProductResponse product(String name, String price, int stock) {
        ProductResponse created = rest.postForObject(
                "/api/products",
                new ProductRequest(name, name + " description", new BigDecimal(price), stock, "IT"),
                ProductResponse.class);
        assertThat(created).isNotNull();
        createdIds.add(created.id());
        return created;
    }

    @Test
    @DisplayName("an item added over HTTP is still there on the next request")
    void addItemPersists() {
        ProductResponse mouse = product("IT Mouse", "25.00", 10);

        ResponseEntity<CartResponse> response = rest.postForEntity(
                "/api/cart/items", new AddCartItemRequest(mouse.id(), 2), CartResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Read it back with a SECOND request. The first response could be built from objects
        // still in memory; only a fresh request proves the row was committed.
        CartResponse reloaded = cart();
        assertThat(reloaded.items()).hasSize(1);
        CartItemResponse line = reloaded.items().getFirst();
        assertThat(line.productId()).isEqualTo(mouse.id());
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.lineTotal()).isEqualByComparingTo("50.00");
        assertThat(reloaded.totalAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("adding the same product twice adds to the existing line, it does not duplicate it")
    void addingTwiceMergesTheLine() {
        ProductResponse cable = product("IT Cable", "9.50", 20);

        rest.postForEntity("/api/cart/items", new AddCartItemRequest(cable.id(), 1), CartResponse.class);
        rest.postForEntity("/api/cart/items", new AddCartItemRequest(cable.id(), 3), CartResponse.class);

        CartResponse reloaded = cart();
        assertThat(reloaded.items()).hasSize(1);
        assertThat(reloaded.items().getFirst().quantity()).isEqualTo(4);
        assertThat(reloaded.totalAmount()).isEqualByComparingTo("38.00");
    }

    @Test
    @DisplayName("the total is the sum of the lines across several products")
    void totalsAddUpAcrossLines() {
        ProductResponse hub = product("IT Hub", "45.00", 5);
        ProductResponse pad = product("IT Mouse Pad", "12.25", 5);

        rest.postForEntity("/api/cart/items", new AddCartItemRequest(hub.id(), 1), CartResponse.class);
        rest.postForEntity("/api/cart/items", new AddCartItemRequest(pad.id(), 2), CartResponse.class);

        CartResponse reloaded = cart();
        assertThat(reloaded.items()).hasSize(2);
        assertThat(reloaded.totalAmount()).isEqualByComparingTo("69.50");
    }

    @Test
    @DisplayName("a quantity can be changed and a line removed")
    void updateThenRemove() {
        ProductResponse stand = product("IT Laptop Stand", "60.00", 8);
        rest.postForEntity("/api/cart/items", new AddCartItemRequest(stand.id(), 1), CartResponse.class);

        rest.put("/api/cart/items/" + stand.id(), new UpdateCartItemRequest(3));
        assertThat(cart().totalAmount()).isEqualByComparingTo("180.00");

        rest.delete("/api/cart/items/" + stand.id());
        CartResponse emptied = cart();
        assertThat(emptied.items()).isEmpty();
        assertThat(emptied.totalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("adding an unknown product is a 404 and the cart is untouched")
    void unknownProductReturns404() {
        ResponseEntity<ApiError> response = rest.postForEntity(
                "/api/cart/items", new AddCartItemRequest(999999L, 1), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(cart().items()).isEmpty();
    }

    @Test
    @DisplayName("a quantity below one is rejected before anything is written")
    void invalidQuantityReturns400() {
        ProductResponse dock = product("IT Dock", "120.00", 4);

        ResponseEntity<ApiError> response = rest.postForEntity(
                "/api/cart/items", new AddCartItemRequest(dock.id(), 0), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("quantity");
        assertThat(cart().items()).isEmpty();
    }
}

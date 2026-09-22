package com.ecomdemo.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Checkout over real HTTP and real PostgreSQL — including the oversell race, which until now was
 * only ever run against H2 in the build and against PostgreSQL by hand through the smoke test.
 *
 * <p>Since Phase 8 an order belongs to the account that placed it, so this class shops as a
 * customer of its own and {@link #anotherCustomersOrderIsNotReadable()} checks the rule that
 * matters most about that: one shopper cannot read another's order, even knowing its id.
 *
 * <p>This is the test the "works on H2 ≠ works on PostgreSQL" lesson is really about. Phase 6's
 * safety net is optimistic locking: an {@code UPDATE ... WHERE id = ? AND version = ?} that
 * affects zero rows when someone else got there first. Whether that actually stops an oversell
 * depends on the engine's isolation level, its row locking and how it reports a lost update —
 * none of which H2 in {@code MODE=PostgreSQL} promises to reproduce. Running it here means the
 * claim "the last unit is sold exactly once" is finally made about PostgreSQL itself.
 */
class OrderApiIT extends IntegrationTest {

    private final List<Long> createdIds = new ArrayList<>();

    private TestRestTemplate shopper;
    private TestRestTemplate admin;

    @BeforeEach
    void signInAndEmptyTheCart() {
        shopper = asCustomer("it-order-shopper");
        admin = asAdmin();
        clearCart();
    }

    @AfterEach
    void cleanUp() {
        clearCart();
        // Products that were ordered cannot be deleted while an order references them, so a
        // failure here is expected and ignored; the ids are scoped to this test class anyway.
        createdIds.forEach(id -> admin.delete("/api/products/" + id));
        createdIds.clear();
    }

    private void clearCart() {
        cart().items().forEach(item -> shopper.delete("/api/cart/items/" + item.productId()));
    }

    private CartResponse cart() {
        CartResponse body = shopper.getForObject("/api/cart", CartResponse.class);
        assertThat(body).isNotNull();
        return body;
    }

    private ProductResponse product(String name, String price, int stock) {
        ProductResponse created = admin.postForObject(
                "/api/products",
                new ProductRequest(name, name + " description", new BigDecimal(price), stock, "IT"),
                ProductResponse.class);
        assertThat(created).isNotNull();
        createdIds.add(created.id());
        return created;
    }

    private int stockOf(long productId) {
        ProductResponse current = rest.getForObject("/api/products/" + productId, ProductResponse.class);
        assertThat(current).isNotNull();
        return current.stockQuantity();
    }

    @Test
    @DisplayName("a checkout creates the order, empties the cart and reduces the stock")
    void placeOrderEndToEnd() {
        ProductResponse headset = product("IT Headset", "89.00", 5);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(headset.id(), 2), CartResponse.class);

        ResponseEntity<OrderResponse> response = shopper.postForEntity("/api/orders", null, OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OrderResponse placed = response.getBody();
        assertThat(placed).isNotNull();
        assertThat(placed.id()).isNotNull();
        assertThat(placed.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(placed.totalAmount()).isEqualByComparingTo("178.00");
        assertThat(placed.items()).hasSize(1);
        assertThat(placed.items().getFirst().quantity()).isEqualTo(2);
        assertThat(placed.placedAt()).isNotNull();

        assertThat(cart().items()).isEmpty();
        assertThat(stockOf(headset.id())).isEqualTo(3);

        // Read the order back through its own endpoint: proof it was committed, not just built.
        ResponseEntity<OrderResponse> fetched =
                shopper.getForEntity("/api/orders/" + placed.id(), OrderResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().totalAmount()).isEqualByComparingTo("178.00");
        assertThat(fetched.getBody().items())
                .extracting(OrderItemResponse::productName)
                .containsExactly("IT Headset");
    }

    @Test
    @DisplayName("a checkout that exceeds stock is a 409 and changes nothing at all")
    void insufficientStockRollsEverythingBack() {
        ProductResponse plenty = product("IT Webcam", "70.00", 10);
        ProductResponse scarce = product("IT Tripod", "30.00", 1);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(plenty.id(), 2), CartResponse.class);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(scarce.id(), 3), CartResponse.class);

        ResponseEntity<ApiError> response = shopper.postForEntity("/api/orders", null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        // The transaction rolled back, so the line that WOULD have succeeded is untouched too.
        // On a non-transactional checkout this is the assertion that fails: stock would read 8.
        assertThat(stockOf(plenty.id())).isEqualTo(10);
        assertThat(stockOf(scarce.id())).isEqualTo(1);
        assertThat(cart().items()).hasSize(2);
    }

    @Test
    @DisplayName("checking out an empty cart is a 409")
    void emptyCartIsRejected() {
        ResponseEntity<ApiError> response = shopper.postForEntity("/api/orders", null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).containsIgnoringCase("empty");
    }

    @Test
    @DisplayName("two simultaneous checkouts for the last unit end as one 201 and one 409")
    void theLastUnitIsSoldExactlyOnce() throws Exception {
        ProductResponse lastOne = product("IT Rare Lens", "999.00", 1);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(lastOne.id(), 1), CartResponse.class);

        // Two real HTTP requests on two threads, released together by the latch so they overlap
        // inside the application rather than arriving one after the other.
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<String>> checkout = () -> {
            start.await();
            return shopper.postForEntity("/api/orders", null, String.class);
        };

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = threads.submit(checkout);
            Future<ResponseEntity<String>> second = threads.submit(checkout);
            start.countDown();

            List<HttpStatus> statuses = List.of(
                    (HttpStatus) first.get(30, TimeUnit.SECONDS).getStatusCode(),
                    (HttpStatus) second.get(30, TimeUnit.SECONDS).getStatusCode());

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            threads.shutdownNow();
            assertThat(threads.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // The single unit was sold once: stock is 0, never -1, and exactly one order holds it.
        assertThat(stockOf(lastOne.id())).isZero();

        ResponseEntity<OrderResponse[]> orders = shopper.getForEntity("/api/orders", OrderResponse[].class);
        assertThat(orders.getBody()).isNotNull();
        long ordersHoldingTheLens = List.of(orders.getBody()).stream()
                .filter(order -> order.items().stream()
                        .anyMatch(item -> item.productId().equals(lastOne.id())))
                .count();
        assertThat(ordersHoldingTheLens).isEqualTo(1);

        // The loser's attempt left nothing behind: the winner emptied the shared cart, and the
        // loser rolled back rather than half-committing.
        assertThat(cart().items()).isEmpty();
    }

    @Test
    @DisplayName("one customer cannot read another customer's order, even knowing its id")
    void anotherCustomersOrderIsNotReadable() {
        // Given: our shopper places an order
        ProductResponse book = product("IT Notebook", "12.00", 5);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(book.id(), 1), CartResponse.class);
        OrderResponse mine = shopper.postForEntity("/api/orders", null, OrderResponse.class).getBody();
        assertThat(mine).isNotNull();

        // When: a different customer asks for it by id
        TestRestTemplate otherShopper = asCustomer("it-order-other-shopper");
        ResponseEntity<ApiError> theirAttempt =
                otherShopper.getForEntity("/api/orders/" + mine.id(), ApiError.class);

        // Then: 403. The order exists, and @PostAuthorize on OrderService.findById compares its
        // owner with authentication.name after loading it — the one check that cannot be made
        // before the row is read, because whose order it is, is in the row.
        assertThat(theirAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(theirAttempt.getBody()).isNotNull();
        assertThat(theirAttempt.getBody().status()).isEqualTo(403);

        // And their order list does not contain it either — that filtering happens in the query,
        // so the row never leaves the database in the first place.
        ResponseEntity<OrderResponse[]> theirOrders =
                otherShopper.getForEntity("/api/orders", OrderResponse[].class);
        assertThat(theirOrders.getBody()).isNotNull();
        assertThat(theirOrders.getBody()).extracting(OrderResponse::id).doesNotContain(mine.id());

        // The owner can still read it, of course
        assertThat(shopper.getForEntity("/api/orders/" + mine.id(), OrderResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("checkout needs a CUSTOMER: nobody gets 401, an administrator gets 403")
    void checkoutRequiresACustomer() {
        ResponseEntity<ApiError> anonymous = rest.postForEntity("/api/orders", null, ApiError.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<ApiError> asAdmin = admin.postForEntity("/api/orders", null, ApiError.class);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(rest.getForEntity("/api/orders", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an order records the account that placed it")
    void anOrderKnowsWhoPlacedIt() {
        ProductResponse pen = product("IT Pen", "3.00", 10);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(pen.id(), 1), CartResponse.class);

        OrderResponse placed = shopper.postForEntity("/api/orders", null, OrderResponse.class).getBody();

        assertThat(placed).isNotNull();
        assertThat(placed.username()).isEqualTo("it-order-shopper");
    }
}

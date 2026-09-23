package com.ecomdemo.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.catalog.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The cart over real HTTP and real PostgreSQL.
 *
 * <p>Since Phase 8 the cart belongs to an account, and this class has one of its own — so it no
 * longer shares a cart with {@code OrderApiIT}, which is an improvement the phase gets for free.
 * It still empties the cart before each test and deletes its probe products afterwards, because
 * one account's cart is shared between the tests <em>in</em> this class and the database really
 * commits.
 *
 * <p>Note who does what: the shopper's client adds to the cart, but the products themselves are
 * created by the ADMIN client. A customer creating a product is a 403, which is exactly the rule
 * {@link #cartEndpointsRequireACustomer()} pins down.
 */
class CartApiIT extends IntegrationTest {

    private final List<Long> createdIds = new ArrayList<>();

    private TestRestTemplate shopper;
    private TestRestTemplate admin;

    @BeforeEach
    void signInAndEmptyTheCart() {
        shopper = asCustomer("it-cart-shopper");
        admin = asAdmin();
        clearCart();
        assertThat(cart().items()).isEmpty();
    }

    @AfterEach
    void cleanUp() {
        clearCart();
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

    @Test
    @DisplayName("a cart line keeps the price it was added at, even after the catalogue changes")
    void aLineKeepsThePriceItWasAddedAt() {
        // THE BEHAVIOUR CHANGE OF PHASE 20, pinned so it cannot silently revert.
        //
        // Before this phase a cart line held @ManyToOne Product and priced itself from the live
        // row, so editing a price repriced every existing cart. The cart is going to order-service
        // and the product to catalog-service, with a database each, so that association cannot
        // survive - a line now remembers the name and price it was added at.
        //
        // That is a real change and it is not strictly better; it is the trade a distributed
        // system makes. A cart that repriced itself would need a call to the catalogue on every
        // read of the hottest path there is. It is also arguably the more honest behaviour: the
        // price a shopper was shown is the price they expect at checkout.
        ProductResponse lamp = product("IT Repricing Lamp", "100.00", 10);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(lamp.id(), 2),
                CartResponse.class);

        // The catalogue changes underneath the cart.
        admin.put("/api/products/" + lamp.id(),
                new ProductRequest("IT Repricing Lamp", "repriced", new BigDecimal("999.00"), 10,
                        "IT"));
        assertThat(admin.getForObject("/api/products/" + lamp.id(), ProductResponse.class).price())
                .as("the catalogue really did change")
                .isEqualByComparingTo("999.00");

        // The cart did not follow it.
        CartResponse reloaded = cart();
        assertThat(reloaded.items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.unitPrice())
                            .as("the line keeps the price it was added at")
                            .isEqualByComparingTo("100.00");
                    assertThat(item.productName()).isEqualTo("IT Repricing Lamp");
                });
        assertThat(reloaded.totalAmount())
                .as("and so does the total: 2 x 100.00, not 2 x 999.00")
                .isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("an item added over HTTP is still there on the next request")
    void addItemPersists() {
        ProductResponse mouse = product("IT Mouse", "25.00", 10);

        ResponseEntity<CartResponse> response = shopper.postForEntity(
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

        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(cable.id(), 1), CartResponse.class);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(cable.id(), 3), CartResponse.class);

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

        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(hub.id(), 1), CartResponse.class);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(pad.id(), 2), CartResponse.class);

        CartResponse reloaded = cart();
        assertThat(reloaded.items()).hasSize(2);
        assertThat(reloaded.totalAmount()).isEqualByComparingTo("69.50");
    }

    @Test
    @DisplayName("a quantity can be changed and a line removed")
    void updateThenRemove() {
        ProductResponse stand = product("IT Laptop Stand", "60.00", 8);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(stand.id(), 1), CartResponse.class);

        shopper.put("/api/cart/items/" + stand.id(), new UpdateCartItemRequest(3));
        assertThat(cart().totalAmount()).isEqualByComparingTo("180.00");

        shopper.delete("/api/cart/items/" + stand.id());
        CartResponse emptied = cart();
        assertThat(emptied.items()).isEmpty();
        assertThat(emptied.totalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("adding an unknown product is a 404 and the cart is untouched")
    void unknownProductReturns404() {
        ResponseEntity<ApiError> response = shopper.postForEntity(
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

        ResponseEntity<ApiError> response = shopper.postForEntity(
                "/api/cart/items", new AddCartItemRequest(dock.id(), 0), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("quantity");
        assertThat(cart().items()).isEmpty();
    }

    @Test
    @DisplayName("the cart needs a CUSTOMER: nobody gets 401, an administrator gets 403")
    void cartEndpointsRequireACustomer() {
        // Anonymous: the server does not know who this is, so there is no cart to show
        ResponseEntity<ApiError> anonymous = rest.getForEntity("/api/cart", ApiError.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).isNotNull();
        assertThat(anonymous.getBody().status()).isEqualTo(401);

        // Authenticated as the administrator: refused, because these endpoints act on "my" cart
        // and an administrator has none. Being an admin does not imply being a customer.
        ResponseEntity<ApiError> asAdmin = admin.getForEntity("/api/cart", ApiError.class);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asAdmin.getBody()).isNotNull();
        assertThat(asAdmin.getBody().status()).isEqualTo(403);
    }

    @Test
    @DisplayName("two shoppers have two carts, and neither can see the other's")
    void cartsAreNotSharedBetweenAccounts() {
        // Given: one product, and a second shopper with a client of their own
        ProductResponse hub = product("IT Shared Hub", "45.00", 10);
        TestRestTemplate otherShopper = asCustomer("it-cart-other-shopper");
        otherShopper.getForObject("/api/cart", CartResponse.class).items()
                .forEach(item -> otherShopper.delete("/api/cart/items/" + item.productId()));

        // When: our shopper puts something in their cart
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(hub.id(), 2), CartResponse.class);

        // Then: the other shopper's cart is untouched. Before Phase 8 this was one row shared by
        // the whole world, and this assertion would have found two items sitting in it.
        CartResponse theirs = otherShopper.getForObject("/api/cart", CartResponse.class);
        assertThat(theirs).isNotNull();
        assertThat(theirs.items()).isEmpty();
        assertThat(theirs.id()).as("a cart of their own, not ours").isNotEqualTo(cart().id());
        assertThat(cart().items()).hasSize(1);
    }
}

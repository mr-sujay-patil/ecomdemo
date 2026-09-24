package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.internal.UserRepository;
import com.ecomdemo.order.internal.OrderService;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.clients.catalog.ProductWrite;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.CatalogGateway;
import com.ecomdemo.support.InMemoryCatalogConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.ecomdemo.clients.inventory.InventoryClient;
import com.ecomdemo.support.TestAuthentication;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The one integration test of this phase: the whole place-order flow against a real Spring
 * context and a real (in-memory) database.
 *
 * <p>{@code @SpringBootTest} starts the application the same way {@code main} does — component
 * scanning, auto-configuration, JPA, the lot — so this proves the wiring works, not just the
 * logic. Phase 2 adds the fast, focused unit and slice tests underneath it.
 *
 * <p>Since Phase 8 the cart and the orders belong to somebody, and the services are wrapped by
 * method-security proxies, so the test has to say who it is before it can shop. See
 * {@link TestAuthentication} for why {@code @WithMockUser} is not enough here.
 */
@SpringBootTest
@Import(InMemoryCatalogConfig.class)
class PlaceOrderFlowTest {

    @Autowired
    private CatalogGateway catalogue;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private UserRepository userRepository;

    /** A shopper of this class's own, so its cart cannot collide with another test class's. */
    /**
     * Stock is in inventory-service since Phase 20b, so creating a product and reserving at
     * checkout are both HTTP calls. Mocked here because this test is about the FLOW - cart to
     * order to empty cart - and every claim it makes about stock is covered in the service that
     * owns it.
     */
    @MockitoBean
    private InventoryClient inventory;

    @BeforeEach
    void signIn() {
        TestAuthentication.authenticateAs(
                TestAuthentication.account(userRepository, "flow-test-shopper", Role.CUSTOMER));
    }

    @AfterEach
    void signOut() {
        TestAuthentication.clear();
    }

    @Test
    void placingAnOrderChargesTheCartTotalReservesStockAndEmptiesTheCart() {
        // A product of our own, so the test does not depend on the seeded catalogue.
        ProductSnapshot product = catalogue.create(
                new ProductWrite("Test Widget", "Created by the flow test", new BigDecimal("19.99"), 10, "ACCESSORIES"));

        CartResponse cart = cartService.addItem(new AddCartItemRequest(product.id(), 3));
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.totalAmount()).isEqualByComparingTo("59.97");

        OrderResponse order = orderService.place();

        assertThat(order.id()).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.totalAmount()).isEqualByComparingTo("59.97");
        assertThat(order.items()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(product.id());
            assertThat(line.productName()).isEqualTo("Test Widget");
            assertThat(line.unitPrice()).isEqualByComparingTo("19.99");
            assertThat(line.quantity()).isEqualTo(3);
        });

        // ...inventory was asked to take exactly the ordered quantity...
        //
        // The assertion CHANGED in Phase 20b, and the change is the split in one line. It used to
        // read the stock back and expect 7: create with 10, order 3. Stock is in another service's
        // database now, so this test can see the REQUEST and not the result - what actually
        // happened to the number is asserted in inventory-service, against the row itself.
        //
        // Weaker, and honest about being weaker. The alternative was to stand up a second
        // application to re-assert something already covered where it belongs.
        verify(inventory).reserve(product.id(), "Test Widget", 3);

        // ...the cart is empty again...
        assertThat(cartService.view().items()).isEmpty();
        assertThat(cartService.view().totalAmount()).isEqualByComparingTo("0");

        // ...and the order is readable afterwards.
        assertThat(orderService.findById(order.id()).totalAmount()).isEqualByComparingTo("59.97");

        // Checking out an empty cart is a conflict, not a zero-value order.
        assertThatThrownBy(() -> orderService.place())
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cart is empty");
    }
}

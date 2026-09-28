package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.shared.ConflictException;
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
import org.springframework.jdbc.core.JdbcTemplate;

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
    private JdbcTemplate jdbc;

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
        TestAuthentication.authenticateAs(4201L, "flow-test-shopper");
    }

    @AfterEach
    void signOut() {
        TestAuthentication.clear();
    }

    @Test
    void placingAnOrderChargesTheCartTotalStartsTheSagaAndEmptiesTheCart() {
        // A product of our own, so the test does not depend on the seeded catalogue.
        ProductSnapshot product = catalogue.create(
                new ProductWrite("Test Widget", "Created by the flow test", new BigDecimal("19.99"), 10, "ACCESSORIES"));

        CartResponse cart = cartService.addItem(new AddCartItemRequest(product.id(), 3));
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.totalAmount()).isEqualByComparingTo("59.97");

        OrderResponse order = orderService.place();

        assertThat(order.id()).isNotNull();
        // PENDING: the saga has only just started (Phase 24).
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalAmount()).isEqualByComparingTo("59.97");
        assertThat(order.items()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(product.id());
            assertThat(line.productName()).isEqualTo("Test Widget");
            assertThat(line.unitPrice()).isEqualByComparingTo("19.99");
            assertThat(line.quantity()).isEqualTo(3);
        });

        // ...inventory will be asked to take exactly the ordered quantity...
        //
        // The assertion has changed twice. Until Phase 20b it read the stock back and expected 7.
        // Phase 20b could only see the REQUEST: verify(inventory).reserve(id, name, 3). Since
        // Phase 24 checkout makes no request at all - it writes an OrderCreatedEvent carrying the
        // lines into the outbox, in this transaction, and inventory-service reserves them when it
        // reads it (InventorySagaTest). So the request is now a ROW, and the row is asserted.
        verify(inventory, never()).reserve(anyLong(), anyString(), anyInt());
        assertThat(jdbc.queryForObject(
                        "SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = 'OrderCreatedEvent'",
                        String.class,
                        String.valueOf(order.id())))
                .contains("\"productId\":" + product.id())
                .contains("\"quantity\":3");

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

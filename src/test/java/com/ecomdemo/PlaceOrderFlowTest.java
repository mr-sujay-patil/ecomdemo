package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.order.OrderService;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.product.ProductService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The one integration test of this phase: the whole place-order flow against a real Spring
 * context and a real (in-memory) database.
 *
 * <p>{@code @SpringBootTest} starts the application the same way {@code main} does — component
 * scanning, auto-configuration, JPA, the lot — so this proves the wiring works, not just the
 * logic. Phase 2 adds the fast, focused unit and slice tests underneath it.
 */
@SpringBootTest
class PlaceOrderFlowTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Test
    void placingAnOrderChargesTheCartTotalReducesStockAndEmptiesTheCart() {
        // A product of our own, so the test does not depend on the seeded catalogue.
        ProductResponse product = productService.create(
                new ProductRequest("Test Widget", "Created by the flow test", new BigDecimal("19.99"), 10, "ACCESSORIES"));

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

        // Stock went down by exactly the ordered quantity...
        assertThat(productService.findById(product.id()).stockQuantity()).isEqualTo(7);

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

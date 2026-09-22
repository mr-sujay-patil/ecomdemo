package com.ecomdemo.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartItemResponse;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.support.WithSecurityRules;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web-slice tests for {@link CartController}: the HTTP contract of the cart endpoints, with
 * {@link CartService} mocked away.
 *
 * <p>Every test runs as a CUSTOMER because every cart endpoint requires one. The nested
 * {@link Access} class is where the other two cases — nobody, and an administrator — are
 * checked.
 */
@WebMvcTest(CartController.class)
@WithSecurityRules
@WithMockUser(username = "shopper", roles = "CUSTOMER")
class CartControllerTest {

    private static final CartResponse CART_WITH_ONE_LINE = new CartResponse(
            1L,
            List.of(new CartItemResponse(
                    10L, "Lamp", new BigDecimal("1500.00"), 2, new BigDecimal("3000.00"))),
            new BigDecimal("3000.00"));

    private static final CartResponse EMPTY_CART = new CartResponse(1L, List.of(), BigDecimal.ZERO);

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CartService cartService;

    @Nested
    @DisplayName("GET /api/cart")
    class View {

        @Test
        void view_whenTheCartHasLines_returns200WithTheLinesAndTheTotal() {
            // Given
            when(cartService.view()).thenReturn(CART_WITH_ONE_LINE);

            // When / Then
            assertThat(mvc.get().uri("/api/cart"))
                    .hasStatus(OK)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"id":1,"totalAmount":3000.00,
                             "items":[{"productId":10,"productName":"Lamp","unitPrice":1500.00,
                                       "quantity":2,"lineTotal":3000.00}]}
                            """);
        }

        @Test
        void view_whenTheCartIsEmpty_returns200WithNoLinesAndAZeroTotal() {
            // Given
            when(cartService.view()).thenReturn(EMPTY_CART);

            // When / Then
            assertThat(mvc.get().uri("/api/cart"))
                    .hasStatus(OK)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"id":1,"items":[],"totalAmount":0}
                            """);
        }
    }

    @Nested
    @DisplayName("POST /api/cart/items")
    class AddItem {

        @Test
        void addItem_whenTheBodyIsValid_returns200WithTheUpdatedCart() {
            // Given
            when(cartService.addItem(any(AddCartItemRequest.class))).thenReturn(CART_WITH_ONE_LINE);

            // When / Then: a cart line is not a new resource of its own, so this is 200, not 201
            assertThat(mvc.post()
                            .uri("/api/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId":10,"quantity":2}
                                    """))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$.totalAmount")
                    .asNumber()
                    .isEqualTo(3000.00);
        }

        @Test
        void addItem_whenTheProductDoesNotExist_returns404InTheApiErrorShape() {
            // Given
            when(cartService.addItem(any(AddCartItemRequest.class)))
                    .thenThrow(NotFoundException.product(404L));

            // When / Then
            assertThat(mvc.post()
                            .uri("/api/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId":404,"quantity":1}
                                    """))
                    .hasStatus(NOT_FOUND)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":404,"message":"Product 404 not found"}
                            """);
        }

        @Test
        void addItem_whenQuantityIsZero_returns400AndNeverReachesTheService() {
            // When / Then
            assertThat(mvc.post()
                            .uri("/api/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId":10,"quantity":0}
                                    """))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("quantity must be at least 1");
            verify(cartService, never()).addItem(any());
        }

        @Test
        void addItem_whenProductIdIsMissing_returns400() {
            // When / Then
            assertThat(mvc.post()
                            .uri("/api/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity":1}
                                    """))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("productId is required");
        }
    }

    @Nested
    @DisplayName("PUT /api/cart/items/{productId}")
    class UpdateItem {

        @Test
        void updateItem_whenTheLineExists_returns200WithTheUpdatedCart() {
            // Given
            when(cartService.updateItem(eq(10L), any(UpdateCartItemRequest.class)))
                    .thenReturn(CART_WITH_ONE_LINE);

            // When / Then
            assertThat(mvc.put()
                            .uri("/api/cart/items/10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity":2}
                                    """))
                    .hasStatus(OK);
        }

        @Test
        void updateItem_whenTheProductIsNotInTheCart_returns404() {
            // Given
            when(cartService.updateItem(eq(10L), any(UpdateCartItemRequest.class)))
                    .thenThrow(NotFoundException.cartItem(10L));

            // When / Then
            assertThat(mvc.put()
                            .uri("/api/cart/items/10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity":2}
                                    """))
                    .hasStatus(NOT_FOUND)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("Product 10 is not in the cart");
        }

        @Test
        void updateItem_whenQuantityIsMissing_returns400AndNeverReachesTheService() {
            // When / Then
            assertThat(mvc.put()
                            .uri("/api/cart/items/10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .hasStatus(BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("quantity is required");
            verify(cartService, never()).updateItem(any(), any());
        }
    }

    @Nested
    @DisplayName("DELETE /api/cart/items/{productId}")
    class RemoveItem {

        @Test
        void removeItem_whenTheLineExists_returns200WithTheRemainingCart() {
            // Given
            when(cartService.removeItem(10L)).thenReturn(EMPTY_CART);

            // When / Then
            assertThat(mvc.delete().uri("/api/cart/items/10"))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$.items")
                    .asArray()
                    .isEmpty();
        }

        @Test
        void removeItem_whenTheProductIsNotInTheCart_returns404() {
            // Given
            when(cartService.removeItem(10L)).thenThrow(NotFoundException.cartItem(10L));

            // When / Then
            assertThat(mvc.delete().uri("/api/cart/items/10")).hasStatus(NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("access rules")
    class Access {

        @Test
        @WithAnonymousUser
        void everyCartEndpoint_whenAnonymous_returns401() {
            assertThat(mvc.get().uri("/api/cart")).hasStatus(UNAUTHORIZED);
            assertThat(mvc.post().uri("/api/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"productId":10,"quantity":1}"""))
                    .hasStatus(UNAUTHORIZED);
            assertThat(mvc.delete().uri("/api/cart/items/10")).hasStatus(UNAUTHORIZED);
            // and nothing reached the service
            verify(cartService, never()).view();
            verify(cartService, never()).addItem(any());
            verify(cartService, never()).removeItem(any());
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        void everyCartEndpoint_whenAuthenticatedAsAdmin_returns403() {
            assertThat(mvc.get().uri("/api/cart")).hasStatus(FORBIDDEN);
            assertThat(mvc.delete().uri("/api/cart/items/10")).hasStatus(FORBIDDEN);
            verify(cartService, never()).view();
        }

        @Test
        @WithAnonymousUser
        void refusedRequest_returnsTheStandardErrorShape() {
            assertThat(mvc.get().uri("/api/cart"))
                    .hasStatus(UNAUTHORIZED)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":401,"message":"Authentication required. Log in at POST /api/auth/login and send the token as 'Authorization: Bearer <token>'."}
                            """);
        }
    }
}

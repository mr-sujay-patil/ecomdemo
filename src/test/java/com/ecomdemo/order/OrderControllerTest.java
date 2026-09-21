package com.ecomdemo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.GlobalExceptionHandler;
import com.ecomdemo.common.InsufficientStockException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecomdemo.support.WithSecurityRules;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web-slice tests for {@link OrderController}.
 *
 * <p>The two 409 cases matter most here: an empty cart and a short stock line are different
 * business problems that must reach the client as the same status with different messages,
 * which is only true because {@code InsufficientStockException} extends
 * {@code ConflictException} and {@link GlobalExceptionHandler} maps the parent.
 *
 * <p>{@code @WithMockUser} on the class runs every test as a CUSTOMER. It does not log anybody
 * in: it puts a ready-made {@code Authentication} into the {@code SecurityContext} before the
 * request, so the {@code UserDetailsService} and the {@code PasswordEncoder} are never reached.
 * That is exactly what a slice test wants — it is testing the <em>rules</em>, not the login —
 * and it is why no password appears anywhere in this file. The {@link Access} tests below
 * override it per test to check the other roles.
 */
@WebMvcTest(OrderController.class)
@WithSecurityRules
@WithMockUser(username = "shopper", roles = "CUSTOMER")
class OrderControllerTest {

    private static final OrderResponse PLACED_ORDER = new OrderResponse(
            5L,
            Instant.parse("2026-01-01T10:15:30Z"),
            "shopper",
            OrderStatus.PLACED,
            new BigDecimal("3000.00"),
            List.of(new OrderItemResponse(
                    10L, "Lamp", new BigDecimal("1500.00"), 2, new BigDecimal("3000.00"))));

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderService orderService;

    @Nested
    @DisplayName("POST /api/orders")
    class Place {

        @Test
        void place_whenTheCartCanBeCheckedOut_returns201WithALocationHeader() {
            // Given
            when(orderService.place()).thenReturn(PLACED_ORDER);

            // When / Then
            assertThat(mvc.post().uri("/api/orders"))
                    .hasStatus(CREATED)
                    .hasHeader("Location", "/api/orders/5")
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"id":5,"status":"PLACED","totalAmount":3000.00,
                             "placedAt":"2026-01-01T10:15:30Z",
                             "items":[{"productId":10,"productName":"Lamp","unitPrice":1500.00,
                                       "quantity":2,"lineTotal":3000.00}]}
                            """);
        }

        @Test
        void place_whenTheCartIsEmpty_returns409() {
            // Given
            when(orderService.place())
                    .thenThrow(new ConflictException("Cannot place an order: the cart is empty"));

            // When / Then
            assertThat(mvc.post().uri("/api/orders"))
                    .hasStatus(CONFLICT)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":409,"message":"Cannot place an order: the cart is empty"}
                            """);
        }

        @Test
        void place_whenALineExceedsStock_returns409NamingTheShortfall() {
            // Given
            when(orderService.place()).thenThrow(new InsufficientStockException("Lamp", 3, 2));

            // When / Then: a subclass of ConflictException, mapped by the same handler
            assertThat(mvc.post().uri("/api/orders"))
                    .hasStatus(CONFLICT)
                    .bodyJson()
                    .extractingPath("$.message")
                    .isEqualTo("Insufficient stock for 'Lamp': requested 3, available 2");
        }
    }

    @Nested
    @DisplayName("GET /api/orders")
    class List_ {

        @Test
        void list_whenOrdersExist_returns200AndAJsonArray() {
            // Given
            when(orderService.findAll()).thenReturn(List.of(PLACED_ORDER));

            // When / Then
            assertThat(mvc.get().uri("/api/orders"))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$[0].id")
                    .isEqualTo(5);
        }

        @Test
        void list_whenNoOrdersHaveBeenPlaced_returns200AndAnEmptyArray() {
            // Given
            when(orderService.findAll()).thenReturn(List.of());

            // When / Then
            assertThat(mvc.get().uri("/api/orders"))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$")
                    .asArray()
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("GET /api/orders/{id}")
    class Get {

        @Test
        void get_whenTheOrderExists_returns200AndTheOrder() {
            // Given
            when(orderService.findById(5L)).thenReturn(PLACED_ORDER);

            // When / Then
            assertThat(mvc.get().uri("/api/orders/5"))
                    .hasStatus(OK)
                    .bodyJson()
                    .extractingPath("$.status")
                    .isEqualTo("PLACED");
        }

        @Test
        void get_whenTheOrderIsMissing_returns404() {
            // Given
            when(orderService.findById(404L)).thenThrow(NotFoundException.order(404L));

            // When / Then
            assertThat(mvc.get().uri("/api/orders/404"))
                    .hasStatus(NOT_FOUND)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":404,"message":"Order 404 not found"}
                            """);
        }
    }

    @Nested
    @DisplayName("access rules")
    class Access {

        @Test
        @WithAnonymousUser
        void anyOrderEndpoint_whenAnonymous_returns401() {
            // Then: 401, not 403 — the caller has not said who they are, and sending
            // credentials would change the answer
            assertThat(mvc.get().uri("/api/orders")).hasStatus(UNAUTHORIZED);
            assertThat(mvc.post().uri("/api/orders")).hasStatus(UNAUTHORIZED);
            assertThat(mvc.get().uri("/api/orders/5")).hasStatus(UNAUTHORIZED);
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        void anyOrderEndpoint_whenAuthenticatedAsAdmin_returns403() {
            // Then: 403 — we know exactly who this is, and an administrator has no cart and no
            // orders of their own. Being an admin does not imply being a customer.
            assertThat(mvc.get().uri("/api/orders")).hasStatus(FORBIDDEN);
            assertThat(mvc.post().uri("/api/orders")).hasStatus(FORBIDDEN);
            assertThat(mvc.get().uri("/api/orders/5")).hasStatus(FORBIDDEN);
        }

        @Test
        @WithAnonymousUser
        void anonymousRequest_whenRefused_stillGetsTheStandardErrorShape() {
            assertThat(mvc.get().uri("/api/orders"))
                    .hasStatus(UNAUTHORIZED)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":401,"message":"Authentication required. Send HTTP Basic credentials with this request."}
                            """);
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        void forbiddenRequest_whenRefused_stillGetsTheStandardErrorShape() {
            assertThat(mvc.get().uri("/api/orders"))
                    .hasStatus(FORBIDDEN)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"status":403,"message":"Your account does not have permission to perform this action."}
                            """);
        }
    }
}

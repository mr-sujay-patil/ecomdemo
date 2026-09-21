package com.ecomdemo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;

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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web-slice tests for {@link OrderController}.
 *
 * <p>The two 409 cases matter most here: an empty cart and a short stock line are different
 * business problems that must reach the client as the same status with different messages,
 * which is only true because {@code InsufficientStockException} extends
 * {@code ConflictException} and {@link GlobalExceptionHandler} maps the parent.
 */
@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    private static final OrderResponse PLACED_ORDER = new OrderResponse(
            5L,
            Instant.parse("2026-01-01T10:15:30Z"),
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
}

package com.ecomdemo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.InsufficientStockException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.Product;
import com.ecomdemo.product.ProductService;
import com.ecomdemo.support.TestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link OrderService}, the only place in the application where several
 * aggregates change together.
 *
 * <p>Stock is asserted on the {@link Product} entities themselves rather than through the mocks:
 * {@code reduceStock} mutates the object the cart holds, so checking the object proves the
 * arithmetic, while {@code verify(productService).save(...)} proves the change was persisted.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartService cartService;

    @Mock
    private ProductService productService;

    @InjectMocks
    private OrderService orderService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @Nested
    @DisplayName("place")
    class Place {

        @Test
        void place_whenEveryLineIsInStock_savesTheOrderReducesStockAndEmptiesTheCart() {
            // Given: 2 x 1500.00 plus 3 x 100.50
            Product lamp = TestData.product(10L, "Lamp", "1500.00", 9);
            Product cable = TestData.product(11L, "Cable", "100.50", 4);
            Cart cart = TestData.cart(1L);
            cart.addItem(lamp, 2);
            cart.addItem(cable, 3);
            when(cartService.currentCart()).thenReturn(cart);
            when(orderRepository.save(any(Order.class))).thenAnswer(saveReturnsItsArgument());

            // When
            OrderResponse placed = orderService.place();

            // Then
            assertThat(placed.status()).isEqualTo(OrderStatus.PLACED);
            assertThat(placed.totalAmount()).isEqualByComparingTo("3301.50");
            assertThat(placed.items())
                    .extracting(OrderItemResponse::productId, OrderItemResponse::quantity)
                    .containsExactly(tuple(10L, 2), tuple(11L, 3));
            assertThat(lamp.getStockQuantity()).isEqualTo(7);
            assertThat(cable.getStockQuantity()).isEqualTo(1);
            verify(productService).save(lamp);
            verify(productService).save(cable);
            verify(cartService).clearCart(cart);
        }

        @Test
        void place_whenCalled_snapshotsTheNameAndPriceOfEachLine() {
            // Given
            Product lamp = TestData.product(10L, "Lamp", "1500.00", 9);
            when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 2));
            when(orderRepository.save(any(Order.class))).thenAnswer(saveReturnsItsArgument());

            // When
            orderService.place();

            // Then: the line copies the catalogue values, so a later rename or reprice cannot
            // rewrite what the customer was charged
            verify(orderRepository).save(orderCaptor.capture());
            OrderItem line = orderCaptor.getValue().getItems().getFirst();
            assertThat(line.getProductId()).isEqualTo(10L);
            assertThat(line.getProductName()).isEqualTo("Lamp");
            assertThat(line.getUnitPrice()).isEqualByComparingTo("1500.00");
            assertThat(line.lineTotal()).isEqualByComparingTo("3000.00");
        }

        @Test
        void place_whenTheCartIsEmpty_throwsConflictAndWritesNothing() {
            // Given
            when(cartService.currentCart()).thenReturn(TestData.cart(1L));

            // When / Then
            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot place an order: the cart is empty");
            verify(orderRepository, never()).save(any());
            verify(cartService, never()).clearCart(any());
        }

        @Test
        void place_whenALineExceedsStock_throwsInsufficientStockNamingTheShortfall() {
            // Given: 3 wanted, only 2 available
            Product lamp = TestData.product(10L, "Lamp", "1500.00", 2);
            when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 3));

            // When / Then
            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(InsufficientStockException.class)
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Insufficient stock for 'Lamp': requested 3, available 2");
            assertThat(lamp.getStockQuantity()).isEqualTo(2);
            verify(orderRepository, never()).save(any());
            verify(cartService, never()).clearCart(any());
        }

        @Test
        void place_whenALaterLineExceedsStock_leavesTheEarlierLinesStockUntouched() {
            // Given: line 1 is fine, line 2 is short. Every line is checked before any stock is
            // written, so a partial reduction must not happen.
            Product lamp = TestData.product(10L, "Lamp", "1500.00", 9);
            Product cable = TestData.product(11L, "Cable", "100.50", 1);
            Cart cart = TestData.cart(1L);
            cart.addItem(lamp, 2);
            cart.addItem(cable, 3);
            when(cartService.currentCart()).thenReturn(cart);

            // When / Then
            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(InsufficientStockException.class);
            assertThat(lamp.getStockQuantity()).isEqualTo(9);
            assertThat(cable.getStockQuantity()).isEqualTo(1);
            verify(productService, never()).save(any());
        }

        @Test
        void place_whenALineTakesTheLastUnit_succeedsAndLeavesZeroStock() {
            // Given: the boundary — requesting exactly what is available is allowed
            Product lamp = TestData.product(10L, "Lamp", "1500.00", 2);
            when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 2));
            when(orderRepository.save(any(Order.class))).thenAnswer(saveReturnsItsArgument());

            // When
            OrderResponse placed = orderService.place();

            // Then
            assertThat(lamp.getStockQuantity()).isZero();
            assertThat(placed.totalAmount()).isEqualByComparingTo("3000.00");
        }
    }

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        void findAll_whenOrdersExist_returnsThemWithTheirLines() {
            // Given
            when(orderRepository.findAllWithItems())
                    .thenReturn(List.of(order("100.00"), order("250.00")));

            // When
            List<OrderResponse> found = orderService.findAll();

            // Then: compared by value, because BigDecimal.equals() also compares the scale and
            // would call 100.00 and 100.0 different numbers
            assertThat(found)
                    .extracting(OrderResponse::totalAmount)
                    .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactly(new BigDecimal("100.0"), new BigDecimal("250.0"));
            assertThat(found.getFirst().items()).hasSize(1);
        }

        @Test
        void findAll_whenNoOrdersHaveBeenPlaced_returnsEmptyList() {
            // Given
            when(orderRepository.findAllWithItems()).thenReturn(List.of());

            // When / Then
            assertThat(orderService.findAll()).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        void findById_whenTheOrderExists_returnsIt() {
            // Given
            when(orderRepository.findByIdWithItems(5L)).thenReturn(Optional.of(order("100.00")));

            // When
            OrderResponse found = orderService.findById(5L);

            // Then
            assertThat(found.status()).isEqualTo(OrderStatus.PLACED);
            assertThat(found.totalAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        void findById_whenTheOrderIsMissing_throwsNotFound() {
            // Given
            when(orderRepository.findByIdWithItems(404L)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> orderService.findById(404L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Order 404 not found");
        }
    }

    /**
     * A placed order holding a single line worth {@code total}.
     *
     * <p>The amount is a String, never a double: {@code new BigDecimal("100.00")} keeps the exact
     * digits and the scale that were written, while {@code BigDecimal.valueOf(100.00)} goes
     * through a double first and yields 100.0.
     */
    private static Order order(String total) {
        Order order = new Order(Instant.parse("2026-01-01T00:00:00Z"));
        order.addItem(10L, "Lamp", new BigDecimal(total), 1);
        return order;
    }

    private static Answer<Order> saveReturnsItsArgument() {
        return invocation -> invocation.getArgument(0);
    }
}

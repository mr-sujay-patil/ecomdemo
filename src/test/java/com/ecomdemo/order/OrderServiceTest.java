package com.ecomdemo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.common.ConcurrentUpdateException;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.order.dto.OrderResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link OrderService}: the retry budget around a checkout, and the two read
 * methods.
 *
 * <p>The work of a single attempt lives in {@link OrderPlacementService} and is tested in
 * {@code OrderPlacementServiceTest}. Mocking that collaborator here is what lets these tests
 * make an attempt lose its optimistic lock on demand — something no amount of real data would
 * do reliably in a unit test.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPlacementService orderPlacementService;

    @Mock
    private OrderAuditService orderAuditService;

    @InjectMocks
    private OrderService orderService;

    @Nested
    @DisplayName("place")
    class Place {

        @Test
        void place_whenTheFirstAttemptSucceeds_returnsItAndAuditsIt() {
            // Given
            when(orderPlacementService.placeOnce()).thenReturn(placed(7L));

            // When
            OrderResponse order = orderService.place();

            // Then
            assertThat(order.id()).isEqualTo(7L);
            verify(orderPlacementService, times(1)).placeOnce();
            verify(orderAuditService)
                    .record(eq(OrderOutcome.PLACED), eq(7L), eq("Order placed with 1 line(s), total 100.00"));
        }

        @Test
        void place_whenAnAttemptLosesTheOptimisticLock_retriesAndSucceeds() {
            // Given: the first attempt collides with a concurrent checkout, the second does not.
            // Each call is a separate transaction, which is the only reason retrying can work:
            // a failed flush leaves its persistence context unusable.
            when(orderPlacementService.placeOnce())
                    .thenThrow(new OptimisticLockingFailureException("row was changed"))
                    .thenReturn(placed(8L));

            // When
            OrderResponse order = orderService.place();

            // Then
            assertThat(order.id()).isEqualTo(8L);
            verify(orderPlacementService, times(2)).placeOnce();
            verify(orderAuditService).record(eq(OrderOutcome.PLACED), eq(8L), any());
        }

        @Test
        void place_whenEveryAttemptLosesTheOptimisticLock_givesUpWithA409() {
            // Given: contention that does not clear
            when(orderPlacementService.placeOnce())
                    .thenThrow(new OptimisticLockingFailureException("row was changed"));

            // When / Then: a ConflictException, which the exception handler answers with 409.
            // Not a 500 - nothing is broken, the request simply lost every race it ran.
            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(ConcurrentUpdateException.class)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("try again");
            verify(orderPlacementService, times(OrderService.MAX_ATTEMPTS)).placeOnce();
            verify(orderAuditService)
                    .record(eq(OrderOutcome.REJECTED), isNull(), eq("Gave up after 3 concurrent-update conflicts"));
        }

        @Test
        void place_whenTheAttemptIsRejectedOnItsMerits_doesNotRetry() {
            // Given: an empty cart or a short line is not going to fix itself
            when(orderPlacementService.placeOnce())
                    .thenThrow(new ConflictException("Cannot place an order: the cart is empty"));

            // When / Then
            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot place an order: the cart is empty");
            verify(orderPlacementService, times(1)).placeOnce();
            // The rejection was already audited inside the attempt, by the transaction that then
            // rolled back; auditing it again here would double-count it.
            verify(orderAuditService, never()).record(any(), any(), any());
        }

        private static OrderResponse placed(long id) {
            return OrderResponse.from(order("100.00", id));
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

    /** As above, but with the id the database would have assigned. */
    private static Order order(String total, long id) {
        Order order = order(total);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}

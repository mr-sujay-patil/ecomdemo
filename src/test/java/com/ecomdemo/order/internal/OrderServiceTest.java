package com.ecomdemo.order.internal;

import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.Order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.shared.ConcurrentUpdateException;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.shared.InsufficientStockException;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.customer.User;
import com.ecomdemo.metrics.CheckoutMetrics;
import com.ecomdemo.metrics.CheckoutOutcome;
import com.ecomdemo.metrics.MetricNames;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.customer.CurrentUser;
import com.ecomdemo.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.mockito.Spy;
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
 *
 * <p>The {@code @PreAuthorize} and {@code @PostAuthorize} annotations on the service are
 * <strong>not</strong> exercised here and cannot be: method security is applied by a Spring
 * proxy, and these tests call the object directly with {@code new}. That is the right split —
 * this class is about the retry budget and the mapping — and it is why
 * {@code OrderControllerTest} and {@code OrderApiIT} cover the rules instead. A unit test that
 * appeared to prove an authorization rule while bypassing the proxy would be worse than no test
 * at all.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPlacementService orderPlacementService;

    @Mock
    private OrderAuditService orderAuditService;

    @Mock
    private CurrentUser currentUser;

    /**
     * A real registry, not a mock.
     *
     * <p>Mocking {@link CheckoutMetrics} would verify that a method was called; a
     * {@code SimpleMeterRegistry} verifies what the meter actually ended up holding, which is the
     * only thing a dashboard can read. It is in-memory and needs no configuration, so there is no
     * reason to accept the weaker assertion.
     */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private CheckoutMetrics checkoutMetrics = new CheckoutMetrics(meterRegistry);

    @InjectMocks
    private OrderService orderService;

    /** The count in the {@code checkout.duration} timer carrying this {@code outcome} tag. */
    private long checkoutsTagged(String outcome) {
        return meterRegistry
                .get(MetricNames.CHECKOUT_DURATION)
                .tag(MetricNames.TAG_OUTCOME, outcome)
                .timer()
                .count();
    }

    private static final User SHOPPER = TestData.customer();

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
                    .recordAttempt(OrderOutcome.PLACED, 7L, "Order placed with 1 line(s), total 100.00");
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
            verify(orderAuditService).recordAttempt(eq(OrderOutcome.PLACED), eq(8L), any());
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
                    .recordAttempt(eq(OrderOutcome.REJECTED), isNull(), eq("Gave up after 3 concurrent-update conflicts"));
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
            verify(orderAuditService, never()).recordAttempt(any(), any(), any());
        }

        private static OrderResponse placed(long id) {
            return OrderResponse.from(order("100.00", id));
        }
    }

    /**
     * What the meters hold after a checkout — the part a dashboard and an alert rule read.
     *
     * <p>These sit next to the retry tests rather than in {@code CheckoutMetricsTest} because
     * what is under test is not the meters, it is the <em>classification</em>: which of the five
     * outcomes {@code place()} decides an exception means. That decision lives here, and a
     * mistake in it is invisible — every graph still draws, it just draws the wrong line.
     */
    @Nested
    @DisplayName("checkout metrics")
    class CheckoutMeters {

        @Test
        void everyMeterExistsBeforeAnyCheckoutHappens() {
            // The point of pre-registering: a series that reads zero is a fact, an absent series
            // is a silence. An alert on a meter that does not exist yet can never fire.
            assertThat(meterRegistry.get(MetricNames.ORDERS_PLACED).counter().count()).isZero();
            assertThat(meterRegistry.get(MetricNames.ORDER_VALUE).summary().count()).isZero();
            for (CheckoutOutcome outcome : CheckoutOutcome.values()) {
                assertThat(checkoutsTagged(outcome.tagValue())).isZero();
            }
        }

        @Test
        void aPlacedOrderIncrementsTheCounter_recordsItsValue_andTimesItAsPlaced() {
            when(orderPlacementService.placeOnce()).thenReturn(placed(7L));

            orderService.place();

            assertThat(meterRegistry.get(MetricNames.ORDERS_PLACED).counter().count()).isEqualTo(1.0);
            // The summary carries both answers: how much (sum) and how many (count).
            assertThat(meterRegistry.get(MetricNames.ORDER_VALUE).summary().totalAmount())
                    .isEqualTo(100.00);
            assertThat(meterRegistry.get(MetricNames.ORDER_VALUE).summary().count()).isEqualTo(1);
            assertThat(checkoutsTagged("placed")).isEqualTo(1);
        }

        @Test
        void aRetriedButSuccessfulCheckoutIsOneTimedCheckout_notTwo() {
            when(orderPlacementService.placeOnce())
                    .thenThrow(new OptimisticLockingFailureException("row was changed"))
                    .thenReturn(placed(8L));

            orderService.place();

            // Two attempts, one checkout. The customer waited once.
            verify(orderPlacementService, times(2)).placeOnce();
            assertThat(checkoutsTagged("placed")).isEqualTo(1);
        }

        @Test
        void anExhaustedRetryBudgetIsTimedAsConflict_andPlacesNothing() {
            when(orderPlacementService.placeOnce())
                    .thenThrow(new OptimisticLockingFailureException("row was changed"));

            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(ConcurrentUpdateException.class);

            assertThat(checkoutsTagged("conflict")).isEqualTo(1);
            assertThat(checkoutsTagged("empty_cart")).isZero();
            assertThat(meterRegistry.get(MetricNames.ORDERS_PLACED).counter().count()).isZero();
        }

        @Test
        void aShortStockLineIsTimedAsOutOfStock_notAsTheGeneralConflict() {
            // The catch order in place() is what this asserts: InsufficientStockException is a
            // ConflictException, so a general-first catch would label it empty_cart and nobody
            // would ever know.
            when(orderPlacementService.placeOnce())
                    .thenThrow(new InsufficientStockException("Lamp", 3, 2));

            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(InsufficientStockException.class);

            assertThat(checkoutsTagged("out_of_stock")).isEqualTo(1);
            assertThat(checkoutsTagged("empty_cart")).isZero();
            assertThat(checkoutsTagged("conflict")).isZero();
        }

        @Test
        void anEmptyCartIsTimedAsEmptyCart() {
            when(orderPlacementService.placeOnce())
                    .thenThrow(new ConflictException("Cannot place an order: the cart is empty"));

            assertThatThrownBy(() -> orderService.place()).isInstanceOf(ConflictException.class);

            assertThat(checkoutsTagged("empty_cart")).isEqualTo(1);
        }

        @Test
        void anUnexpectedFailureIsStillTimed_andStillPropagates() {
            // The RED "errors" figure is only honest if the unforeseen lands somewhere.
            when(orderPlacementService.placeOnce())
                    .thenThrow(new IllegalStateException("the database went away"));

            assertThatThrownBy(() -> orderService.place())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the database went away");

            assertThat(checkoutsTagged("error")).isEqualTo(1);
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
            when(currentUser.id()).thenReturn(SHOPPER.getId());
            when(orderRepository.findAllByUserIdWithItems(SHOPPER.getId()))
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
            when(currentUser.id()).thenReturn(SHOPPER.getId());
            when(orderRepository.findAllByUserIdWithItems(SHOPPER.getId())).thenReturn(List.of());

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
        Order order = new Order(Instant.parse("2026-01-01T00:00:00Z"), SHOPPER);
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

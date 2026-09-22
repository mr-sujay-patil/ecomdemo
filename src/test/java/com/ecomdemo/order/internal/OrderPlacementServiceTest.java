package com.ecomdemo.order.internal;

import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.OrderItem;
import com.ecomdemo.order.Order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecomdemo.cart.Cart;
import com.ecomdemo.cart.CartService;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.shared.InsufficientStockException;
import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.order.dto.OrderItemResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.customer.User;
import com.ecomdemo.catalog.Product;
import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.inventory.InventoryService;
import com.ecomdemo.customer.CurrentUser;
import com.ecomdemo.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link OrderPlacementService}, the only place in the application where several
 * aggregates change together.
 *
 * <p>These assert the <em>logic</em> of one attempt. They cannot assert the transaction: the
 * class is instantiated here with {@code new} and no Spring proxy wraps it, so
 * {@code @Transactional} does nothing at all in this file. That is not a gap being papered over,
 * it is the division of labour — {@code PlaceOrderFlowTest} and {@code ConcurrentCheckoutTest}
 * run against a real context and a real database, which is the only place atomicity and
 * rollback can honestly be proven.
 *
 * <p>Stock is asserted on the {@link Product} entities themselves rather than through the mocks:
 * {@code reduceStock} mutates the object the cart holds, so checking the object proves the
 * arithmetic, while {@code verify(productService).save(...)} proves the change was persisted.
 *
 * <p><strong>{@link InventoryService} is REAL here, and that is deliberate (Phase 19).</strong>
 * Stock moved out of this class into the inventory module, and a mocked inventory would turn every
 * assertion below about stock arithmetic and about {@code InsufficientStockException} into an
 * assertion that a mock was called — the tests would pass while the behaviour they describe had
 * been deleted. Only the catalogue's persistence is mocked, which keeps
 * {@code verify(productService).save(...)} meaning what it meant before the split: the change was
 * handed to the catalogue to persist.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartService cartService;

    @Mock
    private ProductService productService;

    @Mock
    private OrderAuditService orderAuditService;

    @Mock
    private CurrentUser currentUser;

    /**
     * Phase 17 announced the order through Spring's event publisher; Phase 18 appends it to the
     * outbox instead. The service still knows nothing about Kafka — what changed is that the
     * event is now written to the database rather than handed to a listener, so it commits or
     * rolls back with the order.
     *
     * <p>What this mock cannot show is the part that matters: that the append really does join
     * the order's transaction. A mock has no transaction to join, and {@code MANDATORY} is
     * enforced by Spring's proxy, not by the method. That claim is made against a real database
     * in {@code OutboxWriterIT} and against a real broker in {@code OutboxRelayKafkaIT}.
     */
    @Mock
    private OutboxWriter outbox;

    /**
     * Built by hand rather than with {@code @InjectMocks}, because one collaborator is real. The
     * moment a test needs a genuine object among its mocks, {@code @InjectMocks} stops being the
     * shorter way to write it.
     */
    private OrderPlacementService placementService;

    @BeforeEach
    void setUp() {
        placementService =
                new OrderPlacementService(
                        orderRepository,
                        cartService,
                        new InventoryService(productService),
                        orderAuditService,
                        currentUser,
                        outbox);
    }

    private static final User SHOPPER = TestData.customer();

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @Test
    void placeOnce_whenEveryLineIsInStock_savesTheOrderReducesStockAndEmptiesTheCart() {
        // Given: 2 x 1500.00 plus 3 x 100.50
        Product lamp = TestData.product(10L, "Lamp", "1500.00", 9);
        Product cable = TestData.product(11L, "Cable", "100.50", 4);
        Cart cart = TestData.cart(1L);
        cart.addItem(lamp, 2);
        cart.addItem(cable, 3);
        when(cartService.currentCart()).thenReturn(cart);
        when(currentUser.require()).thenReturn(SHOPPER);
        when(orderRepository.save(any(Order.class))).thenAnswer(saveReturnsItsArgument());

        // When
        OrderResponse placed = placementService.placeOnce();

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
    void placeOnce_whenCalled_snapshotsTheNameAndPriceOfEachLine() {
        // Given
        Product lamp = TestData.product(10L, "Lamp", "1500.00", 9);
        when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 2));
        when(currentUser.require()).thenReturn(SHOPPER);
        when(orderRepository.save(any(Order.class))).thenAnswer(saveReturnsItsArgument());

        // When
        placementService.placeOnce();

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
    void placeOnce_whenTheCartIsEmpty_throwsConflictAndWritesNothing() {
        // Given
        when(cartService.currentCart()).thenReturn(TestData.cart(1L));

        // When / Then
        assertThatThrownBy(() -> placementService.placeOnce())
                .isInstanceOf(ConflictException.class)
                .hasMessage("Cannot place an order: the cart is empty");
        verify(orderRepository, never()).save(any());
        verify(cartService, never()).clearCart(any());
    }

    @Test
    void placeOnce_whenALineExceedsStock_throwsInsufficientStockNamingTheShortfall() {
        // Given: 3 wanted, only 2 available
        Product lamp = TestData.product(10L, "Lamp", "1500.00", 2);
        when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 3));

        // When / Then
        assertThatThrownBy(() -> placementService.placeOnce())
                .isInstanceOf(InsufficientStockException.class)
                .isInstanceOf(ConflictException.class)
                .hasMessage("Insufficient stock for 'Lamp': requested 3, available 2");
        assertThat(lamp.getStockQuantity()).isEqualTo(2);
        verify(orderRepository, never()).save(any());
        verify(cartService, never()).clearCart(any());
    }

    @Test
    void placeOnce_whenALaterLineExceedsStock_leavesTheEarlierLinesStockUntouched() {
        // Given: line 1 is fine, line 2 is short. Every line is checked before any stock is
        // written, so a partial reduction must not happen.
        Product lamp = TestData.product(10L, "Lamp", "1500.00", 9);
        Product cable = TestData.product(11L, "Cable", "100.50", 1);
        Cart cart = TestData.cart(1L);
        cart.addItem(lamp, 2);
        cart.addItem(cable, 3);
        when(cartService.currentCart()).thenReturn(cart);

        // When / Then
        assertThatThrownBy(() -> placementService.placeOnce())
                .isInstanceOf(InsufficientStockException.class);
        assertThat(lamp.getStockQuantity()).isEqualTo(9);
        assertThat(cable.getStockQuantity()).isEqualTo(1);
        verify(productService, never()).save(any());
    }

    @Test
    void placeOnce_whenALineTakesTheLastUnit_succeedsAndLeavesZeroStock() {
        // Given: the boundary — requesting exactly what is available is allowed
        Product lamp = TestData.product(10L, "Lamp", "1500.00", 2);
        when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 2));
        when(currentUser.require()).thenReturn(SHOPPER);
        when(orderRepository.save(any(Order.class))).thenAnswer(saveReturnsItsArgument());

        // When
        OrderResponse placed = placementService.placeOnce();

        // Then
        assertThat(lamp.getStockQuantity()).isZero();
        assertThat(placed.totalAmount()).isEqualByComparingTo("3000.00");
    }

    @Test
    void placeOnce_whenTheCartIsEmpty_auditsTheRejectionInItsOwnTransaction() {
        // Given
        when(cartService.currentCart()).thenReturn(TestData.cart(1L));

        // When / Then: the audit call is the whole point. It is a call to ANOTHER bean, which is
        // what makes REQUIRES_NEW take effect at runtime, and it happens before the exception
        // propagates and rolls this attempt back.
        assertThatThrownBy(() -> placementService.placeOnce()).isInstanceOf(ConflictException.class);
        verify(orderAuditService)
                .recordAttempt(
                        eq(OrderOutcome.REJECTED),
                        isNull(),
                        eq("Cannot place an order: the cart is empty"));
    }

    @Test
    void placeOnce_whenALineExceedsStock_auditsTheRejectionWithTheShortfall() {
        // Given
        Product lamp = TestData.product(10L, "Lamp", "1500.00", 2);
        when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 3));

        // When / Then
        assertThatThrownBy(() -> placementService.placeOnce())
                .isInstanceOf(InsufficientStockException.class);
        verify(orderAuditService)
                .recordAttempt(
                        eq(OrderOutcome.REJECTED),
                        isNull(),
                        eq("Insufficient stock for 'Lamp': requested 3, available 2"));
    }

    @Test
    void placeOnce_whenTheOrderSucceeds_writesNoRejectionAudit() {
        // Given
        Product lamp = TestData.product(10L, "Lamp", "1500.00", 9);
        when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, lamp, 2));
        when(currentUser.require()).thenReturn(SHOPPER);
        when(orderRepository.save(any(Order.class))).thenAnswer(saveReturnsItsArgument());

        // When
        placementService.placeOnce();

        // Then: the PLACED row is written by OrderService AFTER this transaction commits, so
        // nothing is audited from in here on the happy path.
        verify(orderAuditService, never()).recordAttempt(any(), any(), any());
    }

    private static Answer<Order> saveReturnsItsArgument() {
        return invocation -> invocation.getArgument(0);
    }

    @Test
    void placeOnce_whenTheOrderIsPlaced_appendsItToTheOutbox() {
        // The event goes into the outbox inside the order's transaction, so this assertion is
        // about what was recorded and not about Kafka. The event id is generated here, once, and
        // travels with the message - which is what lets the consumer recognise a redelivery (see
        // NotificationServiceTest), and what lets the relay republish a row safely.
        Product product = TestData.product(1L, "Desk Lamp", "1200.00", 5);
        when(cartService.currentCart()).thenReturn(TestData.cartWith(1L, product, 2));
        when(currentUser.require()).thenReturn(SHOPPER);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        placementService.placeOnce();

        ArgumentCaptor<OrderPlacedEvent> captor = ArgumentCaptor.forClass(OrderPlacedEvent.class);
        verify(outbox).append(captor.capture());
        OrderPlacedEvent appended = captor.getValue();
        assertThat(appended.username()).isEqualTo(SHOPPER.getUsername());
        assertThat(appended.itemCount()).isEqualTo(1);
        assertThat(appended.eventId()).isNotNull();
    }

    @Test
    void placeOnce_whenTheCartIsEmpty_appendsNothingToTheOutbox() {
        // The other half of the guarantee, and the half that is easy to forget: an event that
        // announces an order which does not exist is as wrong as an order with no event. A
        // rejected checkout must leave the outbox untouched.
        when(cartService.currentCart()).thenReturn(TestData.cart(1L));

        assertThatThrownBy(() -> placementService.placeOnce())
                .isInstanceOf(ConflictException.class);

        verifyNoInteractions(outbox);
    }
}

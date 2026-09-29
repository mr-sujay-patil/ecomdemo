package com.ecomdemo.order.internal.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecomdemo.order.Order;
import com.ecomdemo.order.internal.OrderRepository;
import com.ecomdemo.order.internal.saga.SagaParticipants.ParticipantUnavailableException;
import com.ecomdemo.order.internal.saga.SagaParticipants.PaymentVerdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Every branch of the reconciler's decision, one test each. The claims here are about ORDER and
 * RESTRAINT - who is asked first, and what is NOT done when an answer is missing - so mocks are the
 * right tool: the rows themselves are {@code SagaDeadlineIT}'s job.
 */
@DisplayName("Saga reconciler")
class SagaReconcilerTest {

    private static final long ORDER_ID = 42L;

    private final OrderRepository orders = mock(OrderRepository.class);
    private final SagaParticipants participants = mock(SagaParticipants.class);
    private final OrderDecisions decisions = mock(OrderDecisions.class);
    private SagaReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new SagaReconciler(orders, participants, decisions, mock(PlatformTransactionManager.class));
        Order pending = new Order(Instant.now(), 7L, "shopper");
        pending.addItem(1L, "Lamp", new BigDecimal("30.00"), 1);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(pending));
        when(decisions.confirm(eq(ORDER_ID), anyString())).thenReturn(true);
        when(decisions.cancel(eq(ORDER_ID), anyString())).thenReturn(true);
    }

    private void paymentSays(PaymentVerdict.Status status, String reason) {
        when(participants.settlePayment(eq(ORDER_ID), any())).thenReturn(new PaymentVerdict(status, reason));
    }

    @Test
    @DisplayName("a completed payment CONFIRMS, and inventory is never asked to give stock back")
    void paidIsConfirmed() {
        paymentSays(PaymentVerdict.Status.COMPLETED, null);

        assertThat(reconciler.reconcile(ORDER_ID)).isEqualTo(SagaReconciler.Outcome.CONFIRMED);
        verify(participants, never()).closeStock(any(), any());
        verify(decisions, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("a void CANCELS, with the void's reason, only AFTER inventory has closed the stock")
    void voidedIsCancelledAfterTheStockIsClosed() {
        paymentSays(PaymentVerdict.Status.VOIDED, "voided: deadline");

        assertThat(reconciler.reconcile(ORDER_ID)).isEqualTo(SagaReconciler.Outcome.CANCELLED);
        InOrder order = inOrder(participants, decisions);
        order.verify(participants).settlePayment(eq(ORDER_ID), any());
        order.verify(participants).closeStock(ORDER_ID, "voided: deadline");
        order.verify(decisions).cancel(ORDER_ID, "voided: deadline");
    }

    @Test
    @DisplayName("a decline whose reply was lost CANCELS with the decline's own reason")
    void declinedIsCancelled() {
        paymentSays(PaymentVerdict.Status.FAILED, "Payment declined: too much");

        assertThat(reconciler.reconcile(ORDER_ID)).isEqualTo(SagaReconciler.Outcome.CANCELLED);
        verify(decisions).cancel(ORDER_ID, "Payment declined: too much");
    }

    @Test
    @DisplayName("payment-service silent: UNKNOWN outcome, so nothing is closed and nothing decided")
    void paymentUnknownDefers() {
        when(participants.settlePayment(eq(ORDER_ID), any()))
                .thenThrow(new ParticipantUnavailableException("timed out", null));

        assertThat(reconciler.reconcile(ORDER_ID)).isEqualTo(SagaReconciler.Outcome.DEFERRED);
        verify(participants, never()).closeStock(any(), any());
        verify(decisions, never()).cancel(any(), any());
        verify(decisions, never()).confirm(any(), any());
    }

    @Test
    @DisplayName("inventory silent: the order is NOT cancelled, or its stock would be held for ever")
    void inventoryUnknownDefers() {
        paymentSays(PaymentVerdict.Status.VOIDED, "voided");
        org.mockito.Mockito.doThrow(new ParticipantUnavailableException("timed out", null))
                .when(participants).closeStock(any(), any());

        assertThat(reconciler.reconcile(ORDER_ID)).isEqualTo(SagaReconciler.Outcome.DEFERRED);
        verify(decisions, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("an order already decided is not settled at all")
    void decidedOrdersAreNotAsked() {
        Order confirmed = new Order(Instant.now(), 7L, "shopper");
        ReflectionTestUtils.setField(confirmed, "status", com.ecomdemo.order.OrderStatus.CONFIRMED);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(confirmed));

        assertThat(reconciler.reconcile(ORDER_ID)).isEqualTo(SagaReconciler.Outcome.ALREADY_DECIDED);
        verify(participants, never()).settlePayment(any(), any());
    }

    @Test
    @DisplayName("a reply that decides the order while we ask wins; we report ALREADY_DECIDED")
    void aLateReplyWins() {
        paymentSays(PaymentVerdict.Status.COMPLETED, null);
        when(decisions.confirm(eq(ORDER_ID), anyString())).thenReturn(false);

        assertThat(reconciler.reconcile(ORDER_ID)).isEqualTo(SagaReconciler.Outcome.ALREADY_DECIDED);
    }
}

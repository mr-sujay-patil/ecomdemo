package com.ecomdemo.order.internal.saga;

import com.ecomdemo.order.Order;
import com.ecomdemo.order.OrderStatus;
import com.ecomdemo.order.internal.OrderRepository;
import com.ecomdemo.order.internal.saga.SagaParticipants.ParticipantUnavailableException;
import com.ecomdemo.order.internal.saga.SagaParticipants.PaymentVerdict;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides one overdue order by asking the services that know what really happened (Phase 32).
 *
 * <h2>Reconciliation, not a blind timeout</h2>
 *
 * <p>The tempting version of a saga deadline is "PENDING too long → CANCELLED". It is wrong in the
 * one case that matters most: a saga that SUCCEEDED but whose last message went missing. The
 * customer has been charged; cancelling would take their money and give them nothing. So before
 * deciding anything this asks payment-service - the owner of the one irreversible step - what
 * happened, and the answer decides:
 *
 * <ul>
 *   <li><strong>COMPLETED</strong>: the saga succeeded and only the reply was lost. CONFIRM.
 *   <li><strong>FAILED</strong> or <strong>VOIDED</strong>: nobody paid, and now nobody can (a void
 *       is recorded so a late charge is refused). Close the order's stock in inventory - give back
 *       what it holds, refuse any later reservation - and CANCEL.
 *   <li><strong>No answer</strong>: the outcome is UNKNOWN. Decide nothing; the next sweep asks
 *       again. Guessing here is how orders get cancelled after they were paid for.
 * </ul>
 *
 * <p>Payment is asked first because it is the step that cannot be undone, and inventory second
 * because a stock release CAN be repeated harmlessly. The order is cancelled only after inventory
 * has confirmed the close: cancelling first would take the order out of PENDING, out of every
 * later sweep, and leave its stock held for ever if the close then failed.
 *
 * <h2>No transaction around the HTTP calls</h2>
 *
 * <p>Each decision is its own short transaction, opened only once the answers are in. Holding a
 * database connection across two network calls with a five-second timeout each is how a slow
 * payment-service would drain the application's connection pool (Phase 30's lesson).
 */
@Component
class SagaReconciler {

    private static final Logger log = LoggerFactory.getLogger(SagaReconciler.class);

    /** What reconciling one order did. The metric's {@code outcome} tag. */
    enum Outcome {
        /** The payment had completed; the order is now CONFIRMED. */
        CONFIRMED,
        /** Nobody paid; stock was closed and the order is now CANCELLED. */
        CANCELLED,
        /** A participant did not answer; the order stays PENDING for the next sweep. */
        DEFERRED,
        /** Something else - a late reply - decided the order first. Nothing to do. */
        ALREADY_DECIDED
    }

    private final OrderRepository orders;
    private final SagaParticipants participants;
    private final OrderDecisions decisions;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    SagaReconciler(
            OrderRepository orders,
            SagaParticipants participants,
            OrderDecisions decisions,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.participants = participants;
        this.decisions = decisions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    Outcome reconcile(Long orderId) {
        Optional<Order> order = readOnly.execute(status -> orders.findById(orderId));
        if (order.isEmpty() || order.get().getStatus() != OrderStatus.PENDING) {
            return Outcome.ALREADY_DECIDED;
        }

        PaymentVerdict verdict;
        try {
            verdict = participants.settlePayment(orderId, order.get().getTotalAmount());
        } catch (ParticipantUnavailableException e) {
            log.warn("Order {} is overdue, but its payment could not be settled; will retry: {}",
                    orderId, e.getMessage());
            return Outcome.DEFERRED;
        }

        if (verdict.paid()) {
            boolean moved = Boolean.TRUE.equals(transaction.execute(status ->
                    decisions.confirm(orderId, "saga deadline: the payment had completed")));
            return moved ? Outcome.CONFIRMED : Outcome.ALREADY_DECIDED;
        }

        String reason = verdict.reason() != null ? verdict.reason() : "Payment was not completed";
        try {
            participants.closeStock(orderId, reason);
        } catch (ParticipantUnavailableException e) {
            log.warn("Order {} is overdue and unpaid, but its stock could not be closed; will retry: {}",
                    orderId, e.getMessage());
            return Outcome.DEFERRED;
        }

        boolean moved = Boolean.TRUE.equals(transaction.execute(status -> decisions.cancel(orderId, reason)));
        return moved ? Outcome.CANCELLED : Outcome.ALREADY_DECIDED;
    }
}

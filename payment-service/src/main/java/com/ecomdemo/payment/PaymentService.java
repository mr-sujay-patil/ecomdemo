package com.ecomdemo.payment;

import com.ecomdemo.outbox.Outbox;
import com.ecomdemo.outbox.ProcessedEvents;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga's third step: charge for an order whose stock is held.
 *
 * <p>One local transaction, with the same three parts as inventory's steps: the
 * {@code processed_event} claim, the {@code payment} row, and the outbox row announcing the
 * outcome. A decline is not an exception - it is an ordinary outcome, recorded and published like
 * a success, and the rest of the saga reacts to it.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** What every payment event is about. Keyed by the ORDER id, like every saga event. */
    static final String AGGREGATE = "Payment";

    /** Why a settled order was voided; also the reason the order service shows the shopper. */
    static final String VOID_REASON =
            "Payment was not attempted before the order's deadline; the order was cancelled";

    private final ProcessedEvents processedEvents;
    private final PaymentRepository payments;
    private final Outbox outbox;
    private final PaymentProperties properties;

    public PaymentService(
            ProcessedEvents processedEvents,
            PaymentRepository payments,
            Outbox outbox,
            PaymentProperties properties) {
        this.processedEvents = processedEvents;
        this.payments = payments;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional
    public void onStockReserved(StockReservedEvent event) {
        if (!processedEvents.claim(event.eventId(), StockReservedEvent.class.getSimpleName())) {
            log.info("Ignoring StockReservedEvent {} for order {}: already handled",
                    event.eventId(), event.orderId());
            return;
        }
        // A second guard, and not a redundant one: processed_event recognises the same EVENT
        // redelivered, and this recognises the same ORDER arriving in a different event. Charging
        // a customer twice is the one mistake a payment step must never make, so it is refused
        // here as well as by the UNIQUE constraint on payment.order_id.
        // Since Phase 32 this is also what makes a VOID stick: an order the saga deadline has
        // settled holds a VOIDED row, and a StockReserved that arrives after it - late, or
        // replayed from the dead-letter topic - stops here without charging.
        Optional<Payment> existing = payments.findByOrderId(event.orderId());
        if (existing.isPresent()) {
            log.warn("Order {} already has a {} payment; not charging it",
                    event.orderId(), existing.get().getStatus());
            return;
        }

        Payment payment = payments.save(decide(event.orderId(), event.totalAmount()));

        if (payment.getStatus() == Payment.Status.COMPLETED) {
            PaymentCompletedEvent completed = PaymentCompletedEvent.of(payment);
            outbox.append(AGGREGATE, payment.getOrderId(), completed.eventId(), completed);
            log.info("Payment {} completed for order {}: {}",
                    payment.getId(), payment.getOrderId(), payment.getAmount());
        } else {
            PaymentFailedEvent failed = PaymentFailedEvent.of(payment);
            outbox.append(AGGREGATE, payment.getOrderId(), failed.eventId(), failed);
            log.info("Payment declined for order {}: {}", payment.getOrderId(), payment.getReason());
        }
    }

    /**
     * The saga deadline's question (Phase 32): what happened to this order's payment? And if
     * nothing has, make sure nothing ever will.
     *
     * <p><strong>Ask and fence in one step.</strong> "Is there a payment?" followed later by
     * "cancel it" leaves a gap: a StockReserved processed between the two would charge an order
     * that is about to be cancelled. So the answer "no payment" is never just reported, it is
     * RECORDED - a VOIDED row in the order's one payment slot ({@code uq_payment_order}) - in the
     * same transaction that looks. After this call the outcome can no longer change.
     *
     * <p>A void is announced as a {@link PaymentFailedEvent}, exactly like a decline, so the rest
     * of the saga reacts as it already does: inventory gives any held stock back, the order service
     * cancels. The caller does both directly as well, because it is here precisely because some
     * message did not get through; the reactions are idempotent, so doing them twice is safe.
     *
     * <p>If a StockReserved commits its payment concurrently, one of the two inserts loses on the
     * unique constraint and its transaction rolls back. The caller's retry ({@link #find}) then
     * reports whichever payment won.
     *
     * @param amount the order's total, which a void records as what was NOT charged
     */
    @Transactional
    public Payment settle(Long orderId, BigDecimal amount) {
        Optional<Payment> existing = payments.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Settling order {}: it already has a {} payment", orderId, existing.get().getStatus());
            return existing.get();
        }

        Payment voided = payments.saveAndFlush(Payment.voided(orderId, amount, VOID_REASON));
        PaymentFailedEvent failed = PaymentFailedEvent.of(voided);
        outbox.append(AGGREGATE, orderId, failed.eventId(), failed);
        log.warn("Settling order {}: no payment was ever attempted; VOIDED it", orderId);
        return voided;
    }

    /** The order's payment, if it has one. */
    @Transactional(readOnly = true)
    public Optional<Payment> find(Long orderId) {
        return payments.findByOrderId(orderId);
    }

    /**
     * The mock's whole "payment provider": decline above the limit, accept everything else.
     *
     * <p>Strictly ABOVE, so a total exactly at the limit is accepted, as a card limit would be.
     */
    Payment decide(Long orderId, BigDecimal amount) {
        BigDecimal limit = properties.declineAbove();
        if (amount.compareTo(limit) > 0) {
            return Payment.failed(
                    orderId,
                    amount,
                    "Payment declined: %s exceeds the limit of %s".formatted(
                            amount.toPlainString(), limit.toPlainString()));
        }
        return Payment.completed(orderId, amount);
    }
}

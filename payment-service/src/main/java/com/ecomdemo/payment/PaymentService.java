package com.ecomdemo.payment;

import com.ecomdemo.outbox.Outbox;
import com.ecomdemo.outbox.ProcessedEvents;
import java.math.BigDecimal;
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
        if (payments.findByOrderId(event.orderId()).isPresent()) {
            log.warn("Order {} already has a payment; not charging it again", event.orderId());
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

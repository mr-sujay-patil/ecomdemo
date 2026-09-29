package com.ecomdemo.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * payment-service's only HTTP endpoint (Phase 32), and it is not a way to charge anyone.
 *
 * <p>Until this phase the service had no API at all: a payment could only be CAUSED, by a
 * StockReserved event. That stays true. What the order service's saga deadline needs is a way to
 * find out what happened to a payment whose event went missing - and to stop it happening later -
 * which is a question, not a command to pay. So it lives under {@code /internal}, which the gateway
 * does not route, and only a SERVICE token may call it (see {@link SecurityConfig}).
 */
@RestController
@RequestMapping("/internal/saga/orders")
class SettlementController {

    private final PaymentService payments;

    SettlementController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * Report the order's payment, voiding it first if none was ever attempted. Idempotent: a second
     * call returns the same answer.
     */
    @PostMapping("/{orderId}/settle")
    SettlementResponse settle(@PathVariable Long orderId, @Valid @RequestBody SettleRequest request) {
        Payment payment;
        try {
            payment = payments.settle(orderId, request.amount());
        } catch (DataIntegrityViolationException raced) {
            // A StockReserved committed a payment between our look and our insert. Its payment is
            // the answer; ours was rolled back.
            payment = payments.find(orderId).orElseThrow(() -> raced);
        }
        return SettlementResponse.of(payment);
    }

    record SettleRequest(@NotNull @PositiveOrZero BigDecimal amount) {}

    /** COMPLETED, FAILED or VOIDED; {@code reason} is null only for COMPLETED. */
    record SettlementResponse(
            Long orderId, Long paymentId, Payment.Status status, BigDecimal amount, String reason) {

        static SettlementResponse of(Payment payment) {
            return new SettlementResponse(
                    payment.getOrderId(), payment.getId(), payment.getStatus(),
                    payment.getAmount(), payment.getReason());
        }
    }
}

package com.ecomdemo.order.internal.saga;

import java.math.BigDecimal;

/**
 * The two questions the saga deadline asks the other services (Phase 32).
 *
 * <p>An interface for the same reason as {@code InventoryGateway}: it is the seam the integration
 * suite replaces with a fake that behaves like the real services, while {@link HttpSagaParticipants}
 * speaks the real protocol, proven end to end by the smoke test.
 *
 * <p>Both calls either answer or throw {@link ParticipantUnavailableException}. A timeout is NOT an
 * answer: the request may have been carried out and only the reply lost. That is the "unknown
 * outcome" every distributed system has to live with, and the reconciler's response to it is to
 * decide nothing and ask again on the next sweep. Both calls are idempotent, which is what makes
 * asking again safe.
 */
public interface SagaParticipants {

    /**
     * Asks payment-service what happened to the order's payment. If nothing did, payment-service
     * VOIDS it, so the answer can no longer change.
     */
    PaymentVerdict settlePayment(Long orderId, BigDecimal amount);

    /**
     * Asks inventory-service to give back whatever the order holds and to refuse it any
     * reservation from now on.
     */
    void closeStock(Long orderId, String reason);

    /** What payment-service reported. {@code reason} is null only for a completed payment. */
    record PaymentVerdict(Status status, String reason) {

        public enum Status {
            COMPLETED,
            FAILED,
            VOIDED
        }

        public boolean paid() {
            return status == Status.COMPLETED;
        }
    }

    /** A participant did not answer, or answered with an error: the outcome is unknown. */
    class ParticipantUnavailableException extends RuntimeException {

        public ParticipantUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.ecomdemo.order.internal.saga;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The saga deadline's settings (Phase 32), under {@code ecomdemo.saga}.
 *
 * @param deadline how long an order may stay PENDING before it is reconciled. Warm, a saga settles
 *     in about 1.5 s, and under the Phase 30 load tests its p99 was 5.9 s; a minute is ten times
 *     that. Too short, and orders whose payment is merely queued get voided and cancelled; too long,
 *     and a shopper stares at "pending" and held stock is unavailable to everyone else.
 * @param sweepInterval how often the sweeper looks for overdue orders. An order is resolved at most
 *     {@code deadline + sweepInterval} after it was placed, if both participants answer.
 * @param batchSize the most orders one sweep reconciles, so a backlog after an outage is worked
 *     through a slice at a time rather than in one enormous burst of HTTP calls
 * @param sweepEnabled whether the scheduled sweep runs at all (tests turn it off and call it)
 * @param paymentBaseUrl payment-service, which settles an order's payment
 * @param inventoryBaseUrl inventory-service, which closes an order's stock
 * @param connectTimeout how long to wait to connect to either participant
 * @param readTimeout how long to wait for either participant's answer. Hitting it is an UNKNOWN
 *     outcome - the call may have succeeded - so the order is simply left for the next sweep.
 */
@ConfigurationProperties(prefix = "ecomdemo.saga")
public record SagaProperties(
        @DefaultValue("1m") Duration deadline,
        @DefaultValue("10s") Duration sweepInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("true") boolean sweepEnabled,
        @DefaultValue("http://localhost:8086") String paymentBaseUrl,
        @DefaultValue("http://localhost:8082") String inventoryBaseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {

    public SagaProperties {
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("ecomdemo.saga.deadline must be positive: " + deadline);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("ecomdemo.saga.batch-size must be at least 1: " + batchSize);
        }
    }
}

package com.ecomdemo.order.internal.saga;

import com.ecomdemo.order.internal.OrderRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The saga's clock (Phase 32): every {@code ecomdemo.saga.sweep-interval}, find the orders that have
 * been PENDING longer than {@code ecomdemo.saga.deadline} and reconcile each one.
 *
 * <h2>Who owns the clock in a choreographed saga</h2>
 *
 * <p>Nobody, until now - which is why an order whose event was dead-lettered stayed PENDING for
 * ever. In an orchestrated saga the orchestrator owns every timeout. Here the natural owner is the
 * service that owns the state being waited on: the order service created the PENDING order, shows
 * it to the shopper, and is the only one that can move it. Inventory and payment each see only
 * their own step and cannot tell "slow" from "lost".
 *
 * <h2>More than one instance</h2>
 *
 * <p>Like every {@code @Scheduled} method, this fires in every running instance. That is safe
 * rather than merely tolerated: settling and closing are idempotent on the far side, and the final
 * decision is the conditional {@code UPDATE ... WHERE status = 'PENDING'}, so two sweeps racing on
 * one order make one decision and the other reports ALREADY_DECIDED. It costs duplicate HTTP calls,
 * not duplicate outcomes.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SagaProperties.class)
class SagaDeadlineSweeper {

    private static final Logger log = LoggerFactory.getLogger(SagaDeadlineSweeper.class);

    private final OrderRepository orders;
    private final SagaReconciler reconciler;
    private final SagaMetrics metrics;
    private final SagaProperties properties;

    SagaDeadlineSweeper(
            OrderRepository orders, SagaReconciler reconciler, SagaMetrics metrics, SagaProperties properties) {
        this.orders = orders;
        this.reconciler = reconciler;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecomdemo.saga.sweep-interval:10s}",
            initialDelayString = "${ecomdemo.saga.sweep-interval:10s}")
    void scheduledSweep() {
        if (properties.sweepEnabled()) {
            sweep(Instant.now());
        }
    }

    /**
     * One sweep, as of {@code now}. Package-visible and clock-injected so a test can ask "what would
     * a sweep a minute from now do?" instead of waiting a minute.
     *
     * @return how many orders ended in each outcome
     */
    Map<SagaReconciler.Outcome, Integer> sweep(Instant now) {
        Instant cutoff = now.minus(properties.deadline());
        List<Long> overdue = orders.findPendingPlacedBefore(cutoff, PageRequest.of(0, properties.batchSize()));

        Map<SagaReconciler.Outcome, Integer> result = new EnumMap<>(SagaReconciler.Outcome.class);
        for (Long orderId : overdue) {
            SagaReconciler.Outcome outcome;
            try {
                outcome = reconciler.reconcile(orderId);
            } catch (RuntimeException e) {
                // One order's surprise must not stop the rest of the batch; it will be found again.
                log.error("Reconciling overdue order {} failed; will retry", orderId, e);
                outcome = SagaReconciler.Outcome.DEFERRED;
            }
            metrics.reconciled(outcome);
            result.merge(outcome, 1, Integer::sum);
        }

        long stillOverdue = orders.countPendingPlacedBefore(cutoff);
        metrics.overdue(stillOverdue);
        if (!overdue.isEmpty()) {
            log.info("Saga deadline sweep: {} overdue order(s) reconciled {}; {} still overdue",
                    overdue.size(), result, stillOverdue);
        }
        return result;
    }
}

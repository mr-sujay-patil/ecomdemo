package com.ecomdemo.order.internal.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The saga deadline's two meters (Phase 32).
 *
 * <ul>
 *   <li>{@code saga.orders.overdue} (gauge): PENDING orders older than the deadline, as the last
 *       sweep left them. Normally 0 - a sweep resolves what it finds. A value that STAYS above 0
 *       means reconciliation cannot decide: a participant is down, and every sweep defers.
 *   <li>{@code saga.reconciliations} (counter, tagged {@code outcome}): what each reconciliation
 *       did. Any CONFIRMED or CANCELLED here is a saga that did not finish on its own - a
 *       dead-lettered or lost message - so its rate is the health of the saga itself.
 * </ul>
 *
 * <p>The gauge reads a number the sweep stores, not a query run on every scrape: Prometheus
 * scrapes every 15 s whether or not anything changed, and a COUNT over the orders table on each
 * scrape would be database load created by looking.
 */
@Component
class SagaMetrics {

    static final String OVERDUE = "saga.orders.overdue";
    static final String RECONCILIATIONS = "saga.reconciliations";

    private final AtomicLong overdue = new AtomicLong();
    private final Map<SagaReconciler.Outcome, Counter> outcomes = new EnumMap<>(SagaReconciler.Outcome.class);

    SagaMetrics(MeterRegistry registry) {
        Gauge.builder(OVERDUE, overdue, AtomicLong::get)
                .description("PENDING orders older than the saga deadline, after the last sweep")
                .register(registry);
        for (SagaReconciler.Outcome outcome : SagaReconciler.Outcome.values()) {
            outcomes.put(outcome, Counter.builder(RECONCILIATIONS)
                    .description("Overdue orders reconciled by the saga deadline, by what it decided")
                    .tag("outcome", outcome.name().toLowerCase())
                    .register(registry));
        }
    }

    void reconciled(SagaReconciler.Outcome outcome) {
        outcomes.get(outcome).increment();
    }

    void overdue(long count) {
        overdue.set(count);
    }
}

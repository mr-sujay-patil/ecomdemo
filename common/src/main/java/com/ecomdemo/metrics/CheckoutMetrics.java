package com.ecomdemo.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The three business meters for checkout, and the only place that touches the registry for them.
 *
 * <p>Instrumentation is kept out of {@code OrderService} on purpose. Checkout is the one method
 * in this application with genuinely delicate control flow — a retry loop whose transaction
 * boundary is in another bean — and threading {@code registry.counter(...).increment()} calls
 * through it would bury that logic in bookkeeping. Here the call site reads as three sentences:
 * start, then placed or failed.
 *
 * <p><strong>Every meter is registered in the constructor, before any order is placed.</strong>
 * This is the part that is easy to get wrong and expensive to discover. A meter that has never
 * been touched does not appear in the scrape at all, so a counter for something rare is simply
 * absent until the first occurrence. PromQL over an absent series returns no rows — not zero —
 * so a panel shows "No data" and, far worse, an alert written as
 * {@code rate(...) > 0} can never fire, because there is nothing for the expression to be true
 * about. Registering up front means the series exists at 0 from the first scrape, and "no orders
 * in the last hour" becomes a fact the monitoring system can see rather than a silence it cannot
 * distinguish from health.
 *
 * <p>It is also why the timers are pre-built one per {@link CheckoutOutcome} rather than looked
 * up by tag on demand.
 */
@Component
public class CheckoutMetrics {

    private final Counter ordersPlaced;
    private final DistributionSummary orderValue;
    private final Map<CheckoutOutcome, Timer> checkoutTimers = new EnumMap<>(CheckoutOutcome.class);
    private final MeterRegistry registry;

    public CheckoutMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.ordersPlaced = Counter.builder(MetricNames.ORDERS_PLACED)
                .description("Orders successfully placed and committed")
                .register(registry);

        // No baseUnit. Setting one would make the Prometheus registry fold it into the metric
        // name (order_value_INR_sum), which bakes a currency into every dashboard query and into
        // the day the shop sells in a second one. The unit belongs in the description until
        // there is a currency tag to carry it properly.
        this.orderValue = DistributionSummary.builder(MetricNames.ORDER_VALUE)
                .description("Total amount of each placed order, in the shop's currency")
                .register(registry);

        for (CheckoutOutcome outcome : CheckoutOutcome.values()) {
            checkoutTimers.put(
                    outcome,
                    Timer.builder(MetricNames.CHECKOUT_DURATION)
                            .description("Time taken by one checkout, including any retries")
                            .tag(MetricNames.TAG_OUTCOME, outcome.tagValue())
                            .register(registry));
        }
    }

    /**
     * Starts the clock for one checkout.
     *
     * <p>A {@link Timer.Sample} rather than a {@code System.nanoTime()} of our own, because the
     * sample takes its reading from the registry's {@code Clock} — the same clock the registry
     * uses everywhere else, and one a test can replace with a {@code MockClock} to assert on an
     * exact duration instead of on "something greater than zero".
     *
     * <p>Which timer the sample lands in is not decided here. That is the point of the split: the
     * outcome is not known until the work is done, and a sample can be stopped against any timer.
     */
    public Timer.Sample start() {
        return Timer.start(registry);
    }

    /**
     * Records a successful checkout: the counter, the order's value, and the elapsed time.
     *
     * @param sample the sample returned by {@link #start()}
     * @param totalAmount the order total, which is recorded into {@code order.value}
     */
    public void placed(Timer.Sample sample, BigDecimal totalAmount) {
        // doubleValue() is a deliberate narrowing at the boundary. Money is BigDecimal everywhere
        // it is added up or charged; a metric is a sampled, aggregated statistic that Prometheus
        // stores as a float64 regardless. Nothing is billed from this number - it is only ever
        // summed to graph revenue - and pretending a time series carries exact decimal arithmetic
        // would be the more misleading choice.
        orderValue.record(totalAmount.doubleValue());
        ordersPlaced.increment();
        stop(sample, CheckoutOutcome.PLACED);
    }

    /**
     * Records a checkout that did not produce an order, tagged with why.
     *
     * <p>The elapsed time of a failure is recorded too, and it is worth having: a checkout that
     * fails in 4ms failed its cart check, and one that fails after 3 seconds spent all of it
     * losing optimistic locks. Dropping failures from the timer would also break the RED rate,
     * since the timer's count is what the request rate is derived from.
     */
    public void failed(Timer.Sample sample, CheckoutOutcome outcome) {
        stop(sample, outcome);
    }

    private void stop(Timer.Sample sample, CheckoutOutcome outcome) {
        sample.stop(checkoutTimers.get(outcome));
    }
}

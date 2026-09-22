package com.ecomdemo.metrics;

/**
 * The names and tag keys of every meter this application registers itself.
 *
 * <p>Constants rather than string literals at the call site, for a reason specific to metrics: a
 * meter name is a <em>public interface</em>. A dashboard panel, an alert rule and a recording
 * rule all refer to it by name from outside the codebase, where the compiler cannot see them.
 * Renaming {@code orders.placed} in a moment of tidiness does not break the build — it silently
 * empties a graph and, far worse, permanently silences an alert that will now never fire again.
 *
 * <p>Gathering them here does not make renaming safe, but it makes the blast radius visible, and
 * it gives {@code DashboardMetricsTest} a single list to check the shipped dashboard and alert
 * rules against.
 *
 * <p><strong>On naming.</strong> Micrometer's convention is lowercase, dot-separated, and
 * hierarchical from the general to the specific — {@code order.value}, not {@code valueOfOrder}.
 * Each registry then translates that into its own dialect; the Prometheus one replaces the dots
 * with underscores and appends a unit or type suffix, so {@code orders.placed} is scraped as
 * {@code orders_placed_total} and the {@code checkout.duration} timer as
 * {@code checkout_duration_seconds_count} / {@code _sum} / {@code _bucket}. Writing the
 * Prometheus spelling in the Java code would be writing one registry's dialect into a
 * vendor-neutral API.
 */
public final class MetricNames {

    /**
     * Counter: one increment per order that was actually placed and committed.
     *
     * <p>Scraped as {@code orders_placed_total}. A counter only ever goes up and resets to zero
     * when the process restarts, which is why nothing ever graphs its raw value —
     * {@code rate(orders_placed_total[5m])} is the useful question ("orders per second"), and
     * {@code rate()} understands that a drop to zero is a restart and not negative traffic.
     */
    public static final String ORDERS_PLACED = "orders.placed";

    /**
     * Distribution summary: the total amount of each placed order.
     *
     * <p>A summary, not a counter, because two different questions are being asked of the same
     * event — "how much revenue" ({@code _sum}) and "how big is a typical basket"
     * ({@code _count}, and the buckets between them). A counter of the amount could answer the
     * first and never the second: the average of a stream of numbers is not recoverable from
     * their total alone.
     */
    public static final String ORDER_VALUE = "order.value";

    /**
     * Timer: how long one checkout took, from the first attempt to the final answer.
     *
     * <p>This single meter answers all three RED questions. Rate is
     * {@code rate(checkout_duration_seconds_count[5m])}, because a timer counts as well as
     * times. Errors is the same rate filtered by the {@link #TAG_OUTCOME} tag. Duration is
     * {@code histogram_quantile()} over the buckets. That is why the timer wraps the retry loop
     * rather than a single attempt — the customer waits for the whole thing, and a
     * per-attempt timer would report three fast failures as three fast checkouts.
     */
    public static final String CHECKOUT_DURATION = "checkout.duration";

    /**
     * Tag key on {@link #CHECKOUT_DURATION}: how the checkout ended.
     *
     * <p>Tags multiply time series — one per distinct value, per meter, forever — so the values
     * must be a small closed set known in advance. {@link CheckoutOutcome} is that set. Tagging
     * by anything unbounded (an order id, a username, an exception message) is the classic way
     * to take a monitoring system down with the traffic of the thing it is monitoring.
     */
    public static final String TAG_OUTCOME = "outcome";

    private MetricNames() {}
}

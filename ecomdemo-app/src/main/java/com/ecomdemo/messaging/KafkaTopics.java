package com.ecomdemo.messaging;

/**
 * Every topic this application produces to or consumes from.
 *
 * <p>Constants for the same reason {@code MetricNames} holds the meter names: a topic name is a
 * public interface with no compiler behind it. It is named in {@code @KafkaListener}, in the
 * {@code NewTopic} beans, in the smoke test and in Kafka UI — and a typo does not fail anything.
 * Producing to a name nobody consumes simply writes messages that are never read, which looks
 * exactly like a consumer that is slow.
 *
 * <p><strong>On the naming.</strong> {@code orders.placed} is {@code <aggregate>.<event>}, past
 * tense, because an event is a record of something that already happened — an order WAS placed,
 * and no consumer can decline it. A topic called {@code order.place} would be a command queue,
 * which is a different thing with different rules: a command has one intended handler and can be
 * rejected, while this topic may grow a second and a third consumer group that the publisher
 * never hears about.
 */
public final class KafkaTopics {

    /**
     * Where an order is announced once it is CONFIRMED. Keyed by order id.
     *
     * <p>Until Phase 24 this was published the moment checkout committed. With the saga, checkout
     * only creates a PENDING order, and "placed" - the thing notification-service thanks the
     * shopper for - is true only once payment has gone through. The contract did not change; the
     * moment it is published did.
     */
    public static final String ORDERS_PLACED = "orders.placed";

    /** The saga's first step (Phase 24): a checkout created a PENDING order. Owned here. */
    public static final String ORDERS_CREATED = "orders.created";

    /** inventory-service could not reserve an order's stock. Read here, to cancel the order. */
    public static final String STOCK_REJECTED = "inventory.stock-rejected";

    /** payment-service took payment. Read here, to confirm the order. */
    public static final String PAYMENTS_COMPLETED = "payments.completed";

    /** payment-service declined. Read here, to cancel the order (inventory compensates by itself). */
    public static final String PAYMENTS_FAILED = "payments.failed";

    /** The order service's consumer group for the saga's replies. */
    public static final String ORDER_SAGA_GROUP = "order-service";

    /**
     * The suffix Spring appends to build the retry topics: {@code orders.placed-retry-0},
     * {@code -retry-1}, and so on, one per attempt.
     */
    public static final String RETRY_SUFFIX = "-retry";

    /**
     * The dead-letter topic: {@code orders.placed-dlt}.
     *
     * <p>Where a message goes when every retry has been spent. Nothing consumes it, and that is
     * the point — a DLT is a queue of messages that need a human. Consuming it automatically
     * would turn "this message cannot be processed" into an infinite loop with extra steps.
     */
    public static final String DLT_SUFFIX = "-dlt";

    /** The consumer group that writes notifications. */
    public static final String NOTIFICATION_GROUP = "ecomdemo-notification";

    private KafkaTopics() {
    }
}

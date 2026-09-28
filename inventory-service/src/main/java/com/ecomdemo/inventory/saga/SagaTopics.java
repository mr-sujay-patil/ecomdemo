package com.ecomdemo.inventory.saga;

/**
 * The saga's topics as inventory-service sees them: two it reads, two it owns.
 *
 * <p>Copied, not shared, like the event records beside it. A topic name is a contract between
 * services that are deployed separately; a shared constants jar would make every service
 * recompile when one renames a topic, which is the coupling the split exists to avoid. The smoke
 * test is what notices when two copies disagree.
 */
public final class SagaTopics {

    /** Published by the order service when a checkout creates a PENDING order. Read here. */
    public static final String ORDERS_CREATED = "orders.created";

    /** Published here when every line of an order is reserved. */
    public static final String STOCK_RESERVED = "inventory.stock-reserved";

    /** Published here when an order cannot be reserved. */
    public static final String STOCK_REJECTED = "inventory.stock-rejected";

    /** Published by payment-service when a charge is declined. Read here, to compensate. */
    public static final String PAYMENTS_FAILED = "payments.failed";

    /** This service's consumer group. One group, two topics: each listener is its own consumer. */
    public static final String GROUP = "inventory-service";

    private SagaTopics() {
    }
}

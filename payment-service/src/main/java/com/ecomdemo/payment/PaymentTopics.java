package com.ecomdemo.payment;

/** The saga's topics as payment-service sees them: one it reads, two it owns. */
public final class PaymentTopics {

    /** Published by inventory-service once an order's stock is held. Read here. */
    public static final String STOCK_RESERVED = "inventory.stock-reserved";

    public static final String PAYMENTS_COMPLETED = "payments.completed";

    public static final String PAYMENTS_FAILED = "payments.failed";

    public static final String GROUP = "payment-service";

    private PaymentTopics() {
    }
}

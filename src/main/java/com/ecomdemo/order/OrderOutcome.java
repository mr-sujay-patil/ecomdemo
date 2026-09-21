package com.ecomdemo.order;

/** How a checkout attempt ended. Stored as the {@code outcome} column of {@code order_audit}. */
public enum OrderOutcome {

    /** The order was placed and committed. */
    PLACED,

    /** The attempt was refused: an empty cart, not enough stock, or too many concurrent retries. */
    REJECTED
}

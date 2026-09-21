package com.ecomdemo.order;

/**
 * The lifecycle of an order. Phase 1 only ever produces {@link #PLACED}; payment and
 * fulfilment states arrive with the saga work in Phase 24.
 */
public enum OrderStatus {
    PLACED
}

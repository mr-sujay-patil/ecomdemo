package com.ecomdemo.order;

/**
 * The lifecycle of an order. Phase 1 had only {@code PLACED}; Phase 24's saga replaced it with
 * three states, because "placed" stopped being the end of the story.
 *
 * <pre>
 *            StockRejected / PaymentFailed
 *   PENDING ────────────────────────────────▶ CANCELLED
 *      │
 *      └──────────── PaymentCompleted ──────▶ CONFIRMED
 * </pre>
 *
 * <p>Only PENDING can move, and only once. That is the order's <strong>semantic lock</strong>:
 * while an order is PENDING, the saga is still deciding its fate, and anything that must not act
 * on an undecided order (a report of sales, a notification that says "thank you") waits for it
 * to leave PENDING.
 */
public enum OrderStatus {

    /** Checkout accepted it; stock and payment are being arranged by the saga. */
    PENDING,

    /** Stock is reserved and payment taken. Terminal. Existing Phase 1-23 orders migrate here. */
    CONFIRMED,

    /** Stock could not be reserved or payment was declined. Terminal; the reason is kept. */
    CANCELLED
}

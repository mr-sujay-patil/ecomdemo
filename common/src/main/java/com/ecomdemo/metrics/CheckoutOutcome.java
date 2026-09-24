package com.ecomdemo.metrics;

/**
 * The closed set of values the {@code outcome} tag on {@code checkout.duration} may take.
 *
 * <p>An enum rather than free strings so that the cardinality of the tag is fixed by the type
 * system: there are exactly five checkout outcomes and there is no code path that can invent a
 * sixth at runtime.
 *
 * <p>Deliberately separate from {@link com.ecomdemo.order.internal.OrderOutcome}, which has two values and
 * records what the audit log needs to know (was an order created, yes or no). Monitoring needs a
 * finer split, because the three ways of not creating one call for three different reactions: a
 * rise in {@code empty_cart} is a front-end bug, a rise in {@code out_of_stock} is a merchandising
 * problem, and a rise in {@code conflict} is contention that a human should look at. Collapsing
 * them into one "failed" series would average away the only part that tells you what to do.
 */
public enum CheckoutOutcome {

    /** The order was created and committed. */
    PLACED("placed"),

    /** Checkout was refused because a line asked for more units than exist. */
    OUT_OF_STOCK("out_of_stock"),

    /** Checkout was refused because the cart was empty. */
    EMPTY_CART("empty_cart"),

    /**
     * Every optimistic-locking retry was used up and the checkout was abandoned.
     *
     * <p>The one outcome here that is a property of the system rather than of the request. It is
     * also the one worth an alert: the others rise because customers did something, this one
     * rises because the application is under contention it cannot absorb.
     */
    CONFLICT("conflict"),

    /**
     * Checkout failed for a reason this application does not model — the catch-all.
     *
     * <p>Without this value the RED "errors" figure would be a lie of omission: an
     * {@code OutOfMemoryError}, a driver timeout or a null dereference would leave the timer
     * unrecorded, so the request rate would fall and the error rate would stay at zero, which
     * reads on a dashboard as "quiet" rather than "broken". The bucket that catches what was not
     * anticipated is the one worth having, because everything anticipated is already handled
     * somewhere else.
     */
    ERROR("error");

    private final String tagValue;

    CheckoutOutcome(String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * The value as it appears in the {@code outcome} label.
     *
     * <p>Lower snake case, not {@code name()}. The enum constant spelling is a Java convention;
     * {@code OUT_OF_STOCK} in a PromQL query would be shouting, and the label values of every
     * meter Spring Boot registers are lowercase. Pinning the wire format to a field rather than
     * to {@code name()} also means renaming the constant cannot quietly rewrite a dashboard.
     */
    public String tagValue() {
        return tagValue;
    }
}

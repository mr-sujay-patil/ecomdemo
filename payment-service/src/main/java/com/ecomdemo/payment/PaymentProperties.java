package com.ecomdemo.payment;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The mock's one rule, bound from {@code ecomdemo.payment.*}.
 *
 * @param declineAbove any order total strictly above this is declined, like a card limit.
 *     Configurable ({@code PAYMENT_DECLINE_ABOVE}) so a failure can be forced on purpose: the
 *     smoke test buys enough to exceed it and watches the saga compensate.
 */
@ConfigurationProperties(prefix = "ecomdemo.payment")
public record PaymentProperties(BigDecimal declineAbove) {

    /** 10,000.00: far above any ordinary cart of the seeded catalogue, easy to exceed on purpose. */
    static final BigDecimal DEFAULT_DECLINE_ABOVE = new BigDecimal("10000.00");

    public PaymentProperties {
        declineAbove = declineAbove == null ? DEFAULT_DECLINE_ABOVE : declineAbove;
        if (declineAbove.signum() < 0) {
            throw new IllegalArgumentException(
                    "ecomdemo.payment.decline-above cannot be negative: " + declineAbove);
        }
    }
}

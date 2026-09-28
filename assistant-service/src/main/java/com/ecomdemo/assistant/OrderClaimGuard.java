package com.ecomdemo.assistant;

import java.util.regex.Pattern;

/**
 * An output guardrail: an answer may state an order's status only if a tool found an order in that
 * same turn (Phase 29).
 *
 * <p>Why it exists, from the evaluation set: customer B asked, as a prompt injection, for customer
 * A's orders. The authorization boundary held - B's token read nothing, and the answer cited no
 * order - but qwen2.5 answered anyway with "Order 1: PENDING, Order 2: CONFIRMED". Invented, and
 * indistinguishable from a leak to whoever reads it. A prompt can ask the model not to do that; only
 * a check on the output can make sure.
 *
 * <p>Deliberately narrow: a status word in an answer that names a specific order ("order 30"), with
 * no order looked up. Explaining what PENDING means - the shipping policy does - names no order and
 * passes. It cannot tell a true claim from a false one; it only knows whether the answer has
 * anything to stand on.
 */
final class OrderClaimGuard {

    static final String REPLACEMENT =
            "I can only see your own orders, and I could not find that order on your account.";

    /** "order 30", "Order #30", "order number 30": a SPECIFIC order, not orders in general. */
    private static final Pattern ORDER = Pattern.compile("\\border\\s*(?:no\\.?|number|#)?\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS = Pattern.compile("\\b(pending|confirmed|cancell?ed)\\b", Pattern.CASE_INSENSITIVE);

    private OrderClaimGuard() {
    }

    /** True when the answer states an order status that no order lookup in this turn backs. */
    static boolean unsupported(String answer, boolean anyOrderFound) {
        return !anyOrderFound && ORDER.matcher(answer).find() && STATUS.matcher(answer).find();
    }
}

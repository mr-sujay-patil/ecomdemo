package com.ecomdemo.notification.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The order-placed message, as THIS side reads it.
 *
 * <p><strong>Deliberately a local record rather than a shared type imported from order-service</strong>,
 * which is the same judgement Phase 20b made for the stock-changed event. Sharing the class would make
 * the two services compile against one definition — a compile-time coupling between things that are
 * supposed to be independently deployable, and exactly the mistake that produces a distributed
 * monolith. Each side declares the shape it needs; the contract is the JSON on the topic, not a jar.
 *
 * <p>It is also strictly less than the producer's version, and that is the point of declaring it
 * separately: order-service's record has a static factory that mints an event id, because it is the
 * side that CREATES events. A consumer never does.
 *
 * <p>The field names must match the producer's, because that is what Jackson binds on. Nothing in the
 * build checks it — a renamed field would compile on both sides and fail at run time, on a message
 * the producer considers perfectly good. That is the honest cost of not sharing a jar, and the
 * mitigation is the smoke test, which sends a real order through a real broker.
 */
record OrderPlacedEvent(
        UUID eventId,
        Long orderId,
        String username,
        BigDecimal totalAmount,
        int itemCount,
        Instant placedAt) {
}

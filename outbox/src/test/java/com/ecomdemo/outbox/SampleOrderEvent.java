package com.ecomdemo.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An event for the library's own tests, shaped like ecomdemo-app's {@code OrderPlacedEvent}.
 *
 * <p>The library must not depend on any service's events - that is the whole point of it being a
 * library - so its tests bring their own. The shape is deliberately the real one's: a UUID, a
 * {@code BigDecimal} and an {@code Instant} are exactly the fields whose JSON form depends on the
 * mapper, which is what {@code OutboxTest} checks.
 */
public record SampleOrderEvent(
        UUID eventId,
        Long orderId,
        String username,
        BigDecimal totalAmount,
        int itemCount,
        Instant placedAt) {

    public static SampleOrderEvent of(
            Long orderId, String username, BigDecimal totalAmount, int itemCount, Instant placedAt) {
        return new SampleOrderEvent(
                UUID.randomUUID(), orderId, username, totalAmount, itemCount, placedAt);
    }
}

package com.ecomdemo.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The fact that an order was placed, as it travels over Kafka.
 *
 * <p><strong>It is not the {@code Order} entity, deliberately.</strong> A message on a topic is a
 * published contract read by consumers this application does not own and cannot recompile — from
 * Phase 20 that is literally true, and it is already true of anything reading the topic in Kafka
 * UI. Serialising the entity would publish every field it happens to have, including the JPA
 * version column and whatever a later phase adds, and would make a rename in the database a
 * breaking change for every consumer. This record is chosen field by field.
 *
 * @param eventId a unique id for THIS event, generated once by the producer
 * @param orderId the order that was placed
 * @param username who placed it
 * @param totalAmount what it came to
 * @param itemCount how many lines it had
 * @param placedAt when the order was placed, not when the message was sent
 */
public record OrderPlacedEvent(
        UUID eventId,
        Long orderId,
        String username,
        BigDecimal totalAmount,
        int itemCount,
        Instant placedAt) {

    /**
     * The event id is what makes the consumer idempotent, and where it comes from is the whole
     * trick.
     *
     * <p>Kafka delivers at least once: a redelivery after a rebalance, or after a consumer died
     * between doing its work and committing its offset, carries <em>the same bytes</em> — so an
     * id generated here, once, travels with them and is identical on every copy. An id generated
     * by the consumer, or by a database sequence, would be different for each delivery and would
     * recognise nothing. This is the difference between an idempotency key and a primary key.
     */
    public static OrderPlacedEvent of(
            Long orderId, String username, BigDecimal totalAmount, int itemCount, Instant placedAt) {
        return new OrderPlacedEvent(UUID.randomUUID(), orderId, username, totalAmount, itemCount, placedAt);
    }
}

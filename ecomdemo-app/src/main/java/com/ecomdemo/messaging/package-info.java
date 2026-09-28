/**
 * Kafka, and the transactional outbox that feeds it.
 *
 * <p><strong>It depends on no other module of this application, which is the property that makes
 * it reusable.</strong>
 * {@code OrderPlacedEvent} is a hand-written record rather than the {@code Order} entity, and
 * {@code OutboxWriter} takes it as a parameter - so the module that publishes events knows
 * nothing about ordering, and the next aggregate to need an outbox will not have to teach it.
 *
 * <p>What it publishes: the event records, {@code OutboxWriter} for the write side, and
 * {@code KafkaTopics}.
 *
 * <p><strong>Phase 24</strong> moved the outbox machinery itself - the relay, its producer, the
 * table and the tracing - into the {@code outbox} library, because inventory-service and
 * payment-service need the same thing for the saga. This module now depends on that one, and
 * keeps what only this application knows: its events, its topics, and which event goes where.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Messaging",
        allowedDependencies = {"outbox"})
package com.ecomdemo.messaging;

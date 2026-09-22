/**
 * Kafka, and the transactional outbox that feeds it.
 *
 * <p><strong>It depends on no other module, which is the property that makes it reusable.</strong>
 * {@code OrderPlacedEvent} is a hand-written record rather than the {@code Order} entity, and
 * {@code OutboxWriter} takes it as a parameter - so the module that publishes events knows
 * nothing about ordering, and the next aggregate to need an outbox will not have to teach it.
 *
 * <p>What it publishes: the event record, {@code OutboxWriter} for the write side,
 * {@code EventDeduplicator} for consumers, and {@code KafkaTopics}. The outbox relay, its
 * producer, both entities and both repositories are internal - nothing outside needs to know
 * that a row is polled on a timer.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Messaging",
        allowedDependencies = {})
package com.ecomdemo.messaging;

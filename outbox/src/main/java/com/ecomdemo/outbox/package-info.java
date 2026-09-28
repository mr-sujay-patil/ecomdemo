/**
 * Reliable messaging for the saga (Phase 24): the transactional outbox ({@link
 * com.ecomdemo.outbox.Outbox}) for publishing, the idempotent consumer ({@link
 * com.ecomdemo.outbox.ProcessedEvents}) for receiving, and dead-lettering ({@link
 * com.ecomdemo.outbox.SagaListenerErrors}) for the message that cannot be handled at all.
 *
 * <p>Moved out of ecomdemo-app's {@code messaging} module, where the outbox was built in Phase 18.
 * The relay, the sender and the tables stay in {@code internal}; a service sees only the API.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Outbox",
        allowedDependencies = {})
package com.ecomdemo.outbox;

package com.ecomdemo.notification.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Have I already handled this event?", answered once for every consumer.
 *
 * <p>Introduced in Phase 19 so that {@code ProcessedEventRepository} could move into
 * {@code internal}. Before it, {@code notification} imported the repository across a module
 * boundary and did the check itself — which worked, and would have been copied verbatim by the
 * second consumer this application grows.
 *
 * <p>The API is better than the repository it hides, which is the test of whether a boundary was
 * worth drawing. A caller no longer has to know that the answer is an {@code existsById} followed
 * by a {@code save}, that the two must happen in one transaction, or that the marker goes in
 * FIRST. It asks one question and gets a yes or a no.
 *
 * <p>The reasoning behind that ordering is Phase 17's and has not changed: the primary key of
 * {@code processed_event} is the event id from the message, so two consumers racing on the same
 * record do not both pass a check and proceed — the second insert violates the key and that
 * transaction rolls back, leaving exactly one piece of work done.
 */
@Component
public class EventDeduplicator {

    private final ProcessedEventRepository processedEvents;

    public EventDeduplicator(ProcessedEventRepository processedEvents) {
        this.processedEvents = processedEvents;
    }

    /**
     * Claims an event, returning whether this caller is the one that got it.
     *
     * <p><strong>{@code MANDATORY}, and this is the whole reason the method exists rather than
     * being two calls at the call site.</strong> The marker and the work it guards have to commit
     * together: a marker written without the work means the event is never handled and nothing
     * retries, and work done without the marker means a redelivery does it twice. Either half
     * alone is worse than neither. Refusing to run outside a transaction is how that requirement
     * stops depending on every future caller remembering it.
     *
     * @return {@code true} if this is the first time the event has been seen and the caller should
     *     do the work; {@code false} if it has already been handled and the caller should stop
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(java.util.UUID eventId, String eventType) {
        if (processedEvents.existsById(eventId)) {
            return false;
        }
        processedEvents.save(new ProcessedEvent(eventId, eventType));
        return true;
    }
}

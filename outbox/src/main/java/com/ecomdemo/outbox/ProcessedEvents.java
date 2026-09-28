package com.ecomdemo.outbox;

import com.ecomdemo.outbox.internal.ProcessedEvent;
import com.ecomdemo.outbox.internal.ProcessedEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The idempotent consumer: "have I already handled this event?", answered in the caller's
 * transaction.
 *
 * <p>The outbox is the producer's half of reliable messaging and this is the consumer's. The
 * outbox guarantees an event is published AT LEAST once - a relay that crashes between the
 * broker's acknowledgement and marking the row published sends it again. So every consumer in
 * the saga WILL see duplicates, and each must turn "at least once" into "effectively once".
 *
 * <p>This is notification-service's {@code EventDeduplicator} (Phase 19), moved into the library
 * in Phase 24 because inventory, payment and the order service now need exactly the same thing.
 * notification-service keeps its own copy for now; switching it over is a follow-up, not this
 * phase's scope.
 *
 * <p>Why the marker goes in FIRST, and why the table's primary key is the event id: two
 * consumers racing on the same record cannot both pass the check and proceed - the second insert
 * violates the key, that transaction rolls back, and exactly one piece of work is done.
 */
@Component
public class ProcessedEvents {

    private final ProcessedEventRepository processedEvents;

    public ProcessedEvents(ProcessedEventRepository processedEvents) {
        this.processedEvents = processedEvents;
    }

    /**
     * Claims an event, returning whether this caller is the one that got it.
     *
     * <p>{@code MANDATORY} because the marker and the work it guards have to commit together: a
     * marker without the work means the event is never handled and nothing retries it, and work
     * without the marker means a redelivery does it twice. In the saga "the work" also includes
     * the outbox row for the NEXT step, so one local transaction holds all three: the marker, the
     * state change, and the event announcing it.
     *
     * @return {@code true} if this is the first time the event has been seen and the caller should
     *     do the work; {@code false} if it has already been handled and the caller should stop
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(UUID eventId, String eventType) {
        if (processedEvents.existsById(eventId)) {
            return false;
        }
        processedEvents.save(new ProcessedEvent(eventId, eventType));
        return true;
    }
}

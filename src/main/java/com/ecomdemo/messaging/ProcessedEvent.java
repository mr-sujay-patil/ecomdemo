package com.ecomdemo.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A record that one event has already been handled.
 *
 * <p>The primary key IS the event id, which is the entire design. Checking for a duplicate with a
 * {@code SELECT} and then inserting would be two statements with a gap in between, and two
 * consumers handed the same record after a rebalance would both find nothing and both proceed.
 * Making the id the primary key moves the check into the database's own uniqueness guarantee: the
 * second insert fails, whoever gets there second, with no coordination between them.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    /**
     * No {@code @GeneratedValue}. The id comes from the message and must survive redelivery
     * unchanged — a generated one would be different for every copy and would recognise nothing.
     */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(UUID eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}

package com.ecomdemo.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One event, waiting in the database to be published.
 *
 * <p><strong>The row is the message.</strong> Everything Kafka needs — the key, the payload, the
 * topic it belongs on — is decided when this row is written, inside the order's transaction, and
 * nothing is recomputed later. That is what makes the relay a dumb pipe: it reads bytes and sends
 * them, and cannot accidentally publish a newer version of the order than the one the event
 * describes.
 *
 * <p><strong>Two identifiers, on purpose.</strong> {@link #getId()} is a sequence and means
 * <em>order of publication</em>; {@link #getEventId()} is a UUID and means <em>which event this
 * is</em>. They are not interchangeable. The sequence is local to this table and would be a
 * different number in another database; the UUID travels in the message and is what
 * {@code ProcessedEvent} matches on the consumer side, so it must survive republication
 * unchanged. Collapsing them into one column would mean either sending a sequence number that
 * only means something here, or ordering by a UUID, which has no order.
 *
 * <p><strong>Mutable, unlike most entities here, and only in one direction.</strong> A row is
 * written once and then, at most, marked published or marked failed. There are no setters; the
 * two transitions are methods named after what they mean, so the only changes possible to a
 * committed event are the ones the relay is allowed to make.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    /** The publication order. See the class comment: this is not the event's identity. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The event's identity, as carried in the message.
     *
     * <p>{@code updatable = false} because a republished row must present the same id — that is
     * the entire basis on which the consumer recognises a duplicate. Changing it would turn an
     * at-least-once producer into a producer of infinite distinct events.
     */
    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 100, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    /**
     * The serialised message body, frozen at write time.
     *
     * <p>No {@code length}, because the column is {@code TEXT}: a JPA default of
     * {@code varchar(255)} would be silently wrong the first time an order had enough lines.
     */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until published. The whole state machine, as one nullable column. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(
            UUID eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    /**
     * Records that the broker acknowledged this event.
     *
     * <p>Called only after the send has been <em>confirmed</em>, never after merely being
     * submitted. Marking a row published because {@code send()} returned would mark it published
     * while the bytes were still in the producer's buffer, and the row — the only durable copy —
     * would be gone from the pending set before it was safe. The relay therefore waits for the
     * acknowledgement, which it can afford to do because it is not on a request thread.
     */
    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Records that an attempt failed, and leaves the row pending.
     *
     * <p>Note what this does <em>not</em> do: it does not give up. There is no maximum attempt
     * count and no dead-letter state for an outbox row, because the failure it is built for is
     * "the broker is unreachable", which is temporary by nature and affects every row equally.
     * Abandoning a row after N tries would mean the outage that this phase exists to survive
     * would still lose events — just later, and with a number attached.
     *
     * <p>The counter and the message exist to make the stuck row <em>visible</em>: a row with 400
     * attempts is an alert, not a state transition.
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = truncate(error);
    }

    /** The column is 1000 characters and a stack trace's message is not bounded by anything. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}

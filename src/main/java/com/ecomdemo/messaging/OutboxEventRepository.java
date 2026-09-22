package com.ecomdemo.messaging;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads the pending events, and sweeps the published ones. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The relay's one query: the oldest unpublished events, in the order they were committed.
     *
     * <p>{@code ORDER BY id} rather than {@code created_at}, because id is a sequence and is
     * monotonic by construction — two rows can share a timestamp, and a clock that steps backwards
     * would reorder history. This is served without a sort step by
     * {@code idx_outbox_event_pending (published_at, id)}.
     *
     * <p><strong>Bounded by a {@link Limit}, and that matters more than it looks.</strong> After
     * an outage the pending set is however many orders were placed during it; loading all of them
     * would turn recovery into one enormous transaction that either times out or holds locks for
     * minutes. A batch publishes, commits, and the next tick takes the next batch — so recovery is
     * incremental and its progress is durable at every step.
     */
    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Limit limit);

    /** How many events are waiting. Read by the relay's logging and by the smoke test. */
    long countByPublishedAtIsNull();

    /**
     * Deletes published events older than the cutoff, and returns how many went.
     *
     * <p><strong>{@code published_at IS NOT NULL} is the load-bearing half of this predicate.</strong>
     * A cleanup written as "older than the cutoff" alone would delete rows that are still pending
     * — which is to say, it would delete exactly the events an outage had been holding onto,
     * precisely because the outage made them old. The retention window is about rows that have
     * done their job, and a pending row has not done its job however long it has been waiting.
     *
     * <p>A bulk {@code delete} rather than {@code findAll} then {@code deleteAll}: one statement
     * instead of loading N entities into the persistence context to throw them away.
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}

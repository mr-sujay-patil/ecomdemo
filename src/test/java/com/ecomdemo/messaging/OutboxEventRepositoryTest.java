package com.ecomdemo.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.TestPropertySource;

/**
 * The two queries the outbox lives by: what the relay reads, and what the cleanup deletes.
 *
 * <p>These are worth a database and not a mock. Both are derived or hand-written queries whose
 * correctness is in the SQL — an ordering, a null check, a predicate — and a mocked repository
 * would assert that Spring Data was asked politely rather than that it returned the right rows.
 *
 * <p>The schema is built by Hibernate rather than by the migrations, the same arrangement
 * {@code OrderRepositoryTest} uses: {@code @DataJpaTest} gives each slice a throwaway database and
 * does not run Flyway. {@code FlywayMigrationTest} covers what V10 actually creates.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DisplayName("OutboxEventRepository")
class OutboxEventRepositoryTest {

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private TestEntityManager entityManager;

    private OutboxEvent pending(String orderId) {
        return entityManager.persistAndFlush(
                new OutboxEvent(
                        UUID.randomUUID(),
                        "Order",
                        orderId,
                        OrderPlacedEvent.class.getSimpleName(),
                        "{\"orderId\":" + orderId + "}"));
    }

    /** Persists a row and back-dates its timestamps, which no production code path can do. */
    private OutboxEvent published(String orderId, Instant publishedAt) {
        OutboxEvent event = pending(orderId);
        event.markPublished();
        entityManager.flush();
        entityManager
                .getEntityManager()
                .createQuery("UPDATE OutboxEvent e SET e.publishedAt = :at WHERE e.id = :id")
                .setParameter("at", publishedAt)
                .setParameter("id", event.getId())
                .executeUpdate();
        entityManager.clear();
        return event;
    }

    @Test
    @DisplayName("the relay reads pending events oldest first, and only pending ones")
    void readsPendingInCommitOrder() {
        OutboxEvent first = pending("1");
        OutboxEvent second = pending("2");
        published("3", Instant.now());
        OutboxEvent fourth = pending("4");

        List<OutboxEvent> found = outbox.findByPublishedAtIsNullOrderByIdAsc(Limit.of(10));

        // Ordered by the sequence, which IS the commit order - not by created_at, which two rows
        // can share and which a clock stepping backwards would reorder.
        assertThat(found)
                .extracting(OutboxEvent::getId)
                .containsExactly(first.getId(), second.getId(), fourth.getId());
    }

    @Test
    @DisplayName("the batch size is a real bound, so a backlog cannot become one huge transaction")
    void honoursTheLimit() {
        pending("1");
        pending("2");
        pending("3");

        assertThat(outbox.findByPublishedAtIsNullOrderByIdAsc(Limit.of(2))).hasSize(2);
    }

    @Test
    void countsWhatIsWaiting() {
        pending("1");
        pending("2");
        published("3", Instant.now());

        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(2);
    }

    @Test
    @DisplayName("the sweep deletes published events older than the cutoff")
    void sweepsOldPublishedEvents() {
        published("1", Instant.now().minus(30, ChronoUnit.DAYS));
        published("2", Instant.now());

        int deleted = outbox.deletePublishedBefore(Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        assertThat(outbox.findAll())
                .extracting(OutboxEvent::getAggregateId)
                .containsExactly("2");
    }

    /**
     * The one that matters, and the reason the delete query says {@code published_at IS NOT NULL}.
     *
     * <p>A sweep written as "older than the cutoff" alone would delete this row — an event that
     * has never been published, whose only crime is that an outage kept it waiting long enough to
     * look old. It would delete precisely the events the whole phase exists to protect, at
     * precisely the moment they mattered, and the system would look healthy afterwards because
     * the evidence would be gone.
     *
     * <p>A pending row is never old enough.
     */
    @Test
    @DisplayName("the sweep NEVER deletes a pending event, however old it is")
    void neverSweepsAPendingEvent() {
        OutboxEvent ancient = pending("1");
        entityManager
                .getEntityManager()
                .createQuery("UPDATE OutboxEvent e SET e.createdAt = :at WHERE e.id = :id")
                .setParameter("at", Instant.now().minus(365, ChronoUnit.DAYS))
                .setParameter("id", ancient.getId())
                .executeUpdate();
        entityManager.clear();

        int deleted = outbox.deletePublishedBefore(Instant.now());

        assertThat(deleted).isZero();
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);
    }

    @Test
    @DisplayName("the event id is unique, because republication must reuse it")
    void refusesADuplicateEventId() {
        UUID eventId = UUID.randomUUID();
        entityManager.persistAndFlush(new OutboxEvent(eventId, "Order", "1", "OrderPlacedEvent", "{}"));

        // The consumer recognises a duplicate by this id. Two rows carrying the same one would
        // mean two different events claiming to be the same event, and the consumer would silently
        // drop the second - so the database refuses the situation instead.
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        entityManager.persistAndFlush(
                                                new OutboxEvent(
                                                        eventId, "Order", "2", "OrderPlacedEvent", "{}"))))
                .isNotNull();
    }
}

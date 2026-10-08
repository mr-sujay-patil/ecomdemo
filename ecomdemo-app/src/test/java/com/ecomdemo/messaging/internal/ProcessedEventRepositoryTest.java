package com.ecomdemo.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.outbox.internal.ProcessedEvent;
import com.ecomdemo.outbox.internal.ProcessedEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

/**
 * KI-008. What the prune deletes, against a real database: the predicate is the whole point, and
 * a mock would only prove Spring Data was asked. Same arrangement as {@code OutboxEventRepositoryTest}.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DisplayName("ProcessedEventRepository")
class ProcessedEventRepositoryTest {

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private TestEntityManager entityManager;

    /** Persists a record and back-dates it, which no production code path can do. */
    private UUID recordedAt(Instant at) {
        ProcessedEvent event = entityManager.persistAndFlush(new ProcessedEvent(UUID.randomUUID(), "OrderPlaced"));
        entityManager
                .getEntityManager()
                .createNativeQuery("UPDATE processed_event SET processed_at = ?1 WHERE event_id = ?2")
                .setParameter(1, at)
                .setParameter(2, event.getEventId())
                .executeUpdate();
        entityManager.clear();
        return event.getEventId();
    }

    @Test
    @DisplayName("deletes only the records older than the cutoff")
    void deletesOnlyTheOldOnes() {
        UUID old = recordedAt(Instant.now().minus(40, ChronoUnit.DAYS));
        UUID recent = recordedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        int deleted = processedEvents.deleteProcessedBefore(Instant.now().minus(30, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        assertThat(processedEvents.existsById(old)).isFalse();
        assertThat(processedEvents.existsById(recent)).isTrue();
    }

    @Test
    @DisplayName("deletes nothing when everything is newer than the cutoff")
    void keepsEverythingInsideTheWindow() {
        UUID recent = recordedAt(Instant.now().minus(2, ChronoUnit.DAYS));

        assertThat(processedEvents.deleteProcessedBefore(Instant.now().minus(30, ChronoUnit.DAYS))).isZero();
        assertThat(processedEvents.existsById(recent)).isTrue();
    }
}

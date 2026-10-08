package com.ecomdemo.outbox.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Which events this service has already handled. Used only through {@code ProcessedEvents}. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * KI-008. Deletes the idempotency records recorded before {@code cutoff}, in one statement
     * (the {@code processed_at} index exists for exactly this range scan).
     */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}

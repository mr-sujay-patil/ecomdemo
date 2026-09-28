package com.ecomdemo.outbox.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Which events this service has already handled. Used only through {@code ProcessedEvents}. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}

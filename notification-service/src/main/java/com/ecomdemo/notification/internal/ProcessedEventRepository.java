package com.ecomdemo.notification.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores and looks up the ids of events that have already been handled. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}

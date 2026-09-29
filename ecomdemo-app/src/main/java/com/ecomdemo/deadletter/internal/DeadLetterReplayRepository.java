package com.ecomdemo.deadletter.internal;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeadLetterReplayRepository extends JpaRepository<DeadLetterReplay, Long> {

    boolean existsByDltTopicAndDltPartitionAndDltOffset(String dltTopic, int dltPartition, long dltOffset);

    /** The audit trail, newest first. */
    List<DeadLetterReplay> findTop200ByOrderByReplayedAtDesc();
}

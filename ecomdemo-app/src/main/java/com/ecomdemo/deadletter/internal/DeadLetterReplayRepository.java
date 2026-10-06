package com.ecomdemo.deadletter.internal;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DeadLetterReplayRepository extends JpaRepository<DeadLetterReplay, Long> {

    /**
     * Whether this record was replayed: the same address AND the same write time. A row without a
     * time (from before V21) cannot say, but a replay only ever follows the write it replays: such a
     * row counts only if it was made at or after this record was written. A record written after
     * it is a newer one at a reused address.
     */
    @Query("""
            select count(r) > 0 from DeadLetterReplay r
            where r.dltTopic = :topic and r.dltPartition = :partition and r.dltOffset = :offset
              and (r.dltTimestamp = :timestamp or (r.dltTimestamp is null and r.replayedAt >= :timestamp))""")
    boolean isReplayed(
            @Param("topic") String topic, @Param("partition") int partition, @Param("offset") long offset,
            @Param("timestamp") Instant timestamp);

    /** The audit trail, newest first. */
    List<DeadLetterReplay> findTop200ByOrderByReplayedAtDesc();
}

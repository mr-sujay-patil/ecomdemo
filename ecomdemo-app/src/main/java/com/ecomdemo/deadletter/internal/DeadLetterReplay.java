package com.ecomdemo.deadletter.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One replay of one dead-lettered record: the audit trail (V20). Never updated, never deleted. */
@Entity
@Table(name = "dead_letter_replay")
public class DeadLetterReplay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dlt_topic", nullable = false, length = 200, updatable = false)
    private String dltTopic;

    @Column(name = "dlt_partition", nullable = false, updatable = false)
    private int dltPartition;

    @Column(name = "dlt_offset", nullable = false, updatable = false)
    private long dltOffset;

    /** When the record was written to the dead-letter topic; null on rows from before V21. */
    @Column(name = "dlt_timestamp", updatable = false)
    private Instant dltTimestamp;

    @Column(name = "original_topic", nullable = false, length = 200, updatable = false)
    private String originalTopic;

    @Column(name = "record_key", length = 200, updatable = false)
    private String recordKey;

    @Column(name = "replayed_by", nullable = false, length = 100, updatable = false)
    private String replayedBy;

    @Column(name = "replayed_at", nullable = false, updatable = false)
    private Instant replayedAt;

    protected DeadLetterReplay() {
        // for JPA
    }

    DeadLetterReplay(
            String dltTopic, int dltPartition, long dltOffset, Instant dltTimestamp, String originalTopic,
            String recordKey, String replayedBy, Instant replayedAt) {
        this.dltTopic = dltTopic;
        this.dltPartition = dltPartition;
        this.dltOffset = dltOffset;
        this.dltTimestamp = dltTimestamp;
        this.originalTopic = originalTopic;
        this.recordKey = recordKey;
        this.replayedBy = replayedBy;
        this.replayedAt = replayedAt;
    }

    public Long getId() {
        return id;
    }

    public String getDltTopic() {
        return dltTopic;
    }

    public int getDltPartition() {
        return dltPartition;
    }

    public long getDltOffset() {
        return dltOffset;
    }

    public Instant getDltTimestamp() {
        return dltTimestamp;
    }

    public String getOriginalTopic() {
        return originalTopic;
    }

    public String getRecordKey() {
        return recordKey;
    }

    public String getReplayedBy() {
        return replayedBy;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }
}

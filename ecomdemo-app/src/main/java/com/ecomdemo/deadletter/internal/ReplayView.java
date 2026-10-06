package com.ecomdemo.deadletter.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One entry of the replay audit trail. */
@Schema(description = "A dead-lettered record that was sent back to its topic, and who did it")
public record ReplayView(
        @Schema(description = "The dead-letter topic it was read from", example = "inventory.stock-reserved-dlt")
                String dltTopic,
        @Schema(description = "Its partition there", example = "0") int dltPartition,
        @Schema(description = "Its offset there", example = "3") long dltOffset,
        @Schema(description = "When it was written there: with the address, what identifies the record. "
                + "Null for a replay from before the field existed") Instant dltTimestamp,
        @Schema(description = "The topic it was replayed to", example = "inventory.stock-reserved")
                String originalTopic,
        @Schema(description = "The record key (the order id)", example = "4812") String key,
        @Schema(description = "The administrator who replayed it", example = "admin") String replayedBy,
        @Schema(description = "When") Instant replayedAt) {

    static ReplayView of(DeadLetterReplay replay) {
        return new ReplayView(
                replay.getDltTopic(), replay.getDltPartition(), replay.getDltOffset(), replay.getDltTimestamp(),
                replay.getOriginalTopic(),
                replay.getRecordKey(), replay.getReplayedBy(), replay.getReplayedAt());
    }
}

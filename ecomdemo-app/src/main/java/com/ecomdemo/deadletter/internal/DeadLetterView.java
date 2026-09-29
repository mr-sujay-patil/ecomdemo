package com.ecomdemo.deadletter.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One record waiting in a saga dead-letter topic, and why it is there. */
@Schema(description = "A saga event that could not be processed and was moved to a dead-letter topic")
public record DeadLetterView(
        @Schema(description = "The dead-letter topic", example = "inventory.stock-reserved-dlt") String topic,
        @Schema(description = "Its partition", example = "0") int partition,
        @Schema(description = "Its offset: with topic and partition, the record's address", example = "3")
                long offset,
        @Schema(description = "The record key: the order id, for every saga event", example = "4812") String key,
        @Schema(description = "When it was written to the dead-letter topic") Instant timestamp,
        @Schema(description = "The topic it failed on, where a replay sends it", example = "inventory.stock-reserved")
                String originalTopic,
        @Schema(description = "The exception that dead-lettered it") String exceptionClass,
        @Schema(description = "The exception's message") String exceptionMessage,
        @Schema(description = "The record's value as text, truncated to 2000 characters") String payload,
        @Schema(description = "Whether it has already been replayed (see the replay log)") boolean replayed) {
}

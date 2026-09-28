package com.ecomdemo.catalog.search;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * One backfill run, and the state of the whole index.
 *
 * @param indexedProducts how many products have an embedding right now; equal to
 *     {@code totalProducts} when every product can be found by meaning
 */
public record BackfillStatus(
        long executionId,
        @Schema(example = "COMPLETED") String status,
        @Schema(description = "Products read so far in this run") long read,
        @Schema(description = "Products embedded and saved so far in this run") long written,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        @Schema(description = "Why the run failed, if it did") String failure,
        long indexedProducts,
        long totalProducts) {
}

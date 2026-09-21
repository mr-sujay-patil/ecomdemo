package com.ecomdemo.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The single error shape every failing request returns.
 *
 * <p>A record is enough: an error response is immutable data, it needs no behaviour, and
 * Jackson serialises the components straight to {@code {"status": ..., "message": ...}}.
 */
@Schema(name = "ApiError", description = "The shape every failing request returns.")
public record ApiError(
        @Schema(description = "The HTTP status code, repeated in the body so a client that only logs the body still has it.", example = "404")
        int status,

        @Schema(description = "A human-readable explanation. Validation failures list every rejected field, separated by '; '.", example = "Product 42 not found")
        String message) {
}

package com.ecomdemo.deadletter.internal;

import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's view of dead-lettered saga events (Phase 32). ADMIN only, by the
 * {@code /api/admin/**} rule in {@code SecurityConfig} and at the gateway, like the batch jobs.
 */
@RestController
@RequestMapping("/api/admin/dead-letters")
@Tag(
        name = "Dead letters",
        description =
                "Saga events that could not be processed and were moved to a dead-letter topic. List "
                        + "them with the error that sent them there, and replay one to the topic it "
                        + "came from once the cause is fixed. Every replay is recorded. ADMIN only.")
@SecurityRequirement(name = "bearerAuth")
class DeadLetterController {

    private final DeadLetterService deadLetters;

    DeadLetterController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    @Operation(
            summary = "List dead-lettered saga events",
            description =
                    "Reads the five saga dead-letter topics from the beginning, every time: a dead-letter "
                            + "topic is a record of what went wrong, not a queue to drain. At most 500 "
                            + "records.")
    @ApiResponse(responseCode = "200", description = "Every record waiting, with why it is there")
    @ApiResponse(
            responseCode = "403",
            description = "Not an ADMIN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    List<DeadLetterView> list() {
        return deadLetters.list();
    }

    @PostMapping("/{topic}/{partition}/{offset}/replay")
    @Operation(
            summary = "Replay one dead-lettered event",
            description =
                    "Sends the record back to the topic it failed on, byte for byte, and records who did "
                            + "it. Safe to do: saga consumers ignore an event they have already handled, "
                            + "and an order the saga deadline has decided is fenced against late events. "
                            + "A record can be replayed once.")
    @ApiResponse(responseCode = "200", description = "Replayed; the audit entry")
    @ApiResponse(
            responseCode = "403",
            description = "Not an ADMIN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Not a saga dead-letter topic, or no record at that position",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "That record has already been replayed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    ReplayView replay(
            @Parameter(description = "The dead-letter topic", example = "inventory.stock-reserved-dlt")
            @PathVariable String topic,
            @Parameter(description = "The record's partition", example = "0") @PathVariable int partition,
            @Parameter(description = "The record's offset", example = "3") @PathVariable long offset,
            Principal principal) {
        return deadLetters.replay(topic, partition, offset, principal.getName());
    }

    @GetMapping("/replays")
    @Operation(
            summary = "The replay log",
            description = "Every replay, newest first (the latest 200): which record, where it went, who, when.")
    @ApiResponse(responseCode = "200", description = "The audit trail")
    @ApiResponse(
            responseCode = "403",
            description = "Not an ADMIN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    List<ReplayView> replays() {
        return deadLetters.replays();
    }
}

package com.ecomdemo.catalog.search;

import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The backfill's two endpoints, both ADMIN at the gateway: the POST under the existing
 * "writes under /api/products are ADMIN" rule, the GET under a rule added for
 * {@code /api/products/embeddings/**} ahead of the public GET one.
 */
@RestController
@RequestMapping("/api/products/embeddings/backfill")
@SecurityRequirement(name = "bearerAuth")
public class EmbeddingBackfillController {

    private final EmbeddingBackfillService backfill;

    public EmbeddingBackfillController(EmbeddingBackfillService backfill) {
        this.backfill = backfill;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Embed every product again (ADMIN)",
            description = "Starts the backfill job and returns at once; poll the execution id for progress.")
    @ApiResponse(responseCode = "202", description = "Started")
    @ApiResponse(
            responseCode = "503",
            description = "No embedding model configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public BackfillStatus start() {
        return backfill.start();
    }

    @GetMapping("/{executionId}")
    @Operation(summary = "One backfill run's progress, and how much of the catalogue is indexed (ADMIN)")
    @ApiResponse(responseCode = "200", description = "The run")
    @ApiResponse(
            responseCode = "404",
            description = "No backfill run with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public BackfillStatus status(
            @Parameter(description = "The executionId the POST returned", example = "1") @PathVariable long executionId) {
        return backfill.status(executionId);
    }
}

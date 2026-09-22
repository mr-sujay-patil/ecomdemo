package com.ecomdemo.batch;

import com.ecomdemo.batch.dto.JobExecutionResponse;
import com.ecomdemo.batch.dto.ProductImportResponse;
import com.ecomdemo.common.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The batch jobs' front door: upload a catalogue file, look a run up, restart a failed one.
 *
 * <p>Everything here is ADMIN-only, enforced in {@code SecurityConfig} by the path prefix rather
 * than per method, so an endpoint added to this controller later is protected the moment it
 * exists.
 *
 * <p>Note what a successful POST means. A {@code 200} says <em>the job ran</em>, not that it
 * succeeded: the body's {@code status} is the outcome, and {@code FAILED} arrives with a 200 and
 * a failure message. That is the same distinction the framework itself draws — running a job and
 * the job's result are two different things — and collapsing them into the HTTP status would
 * leave nowhere to report a run that completed with rows skipped.
 */
@RestController
@RequestMapping("/api/admin/batch")
@Tag(
        name = "Batch",
        description =
                "Background jobs. Upload a product CSV to import it, inspect any run by its "
                        + "execution id, and restart a failed import from where it stopped. "
                        + "ADMIN only.")
@SecurityRequirement(name = "bearerAuth")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping(path = "/product-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Import products from a CSV file",
            description =
                    "Runs the import job against the uploaded file and returns when it has "
                            + "finished. The CSV must have the header "
                            + "`name,description,price,stock_quantity,category`. A row whose name "
                            + "matches an existing product updates it; anything else is created. "
                            + "Rows that fail validation are skipped up to the configured limit "
                            + "and listed in the error file named in the response.")
    @ApiResponse(responseCode = "200", description = "The job ran; the body says how it ended")
    @ApiResponse(
            responseCode = "403",
            description = "Not an ADMIN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ProductImportResponse importProducts(
            @Parameter(description = "The CSV file to import") @RequestParam("file") MultipartFile file) {
        return batchService.importProducts(file);
    }

    @GetMapping("/executions/{id}")
    @Operation(
            summary = "Look up one job execution",
            description =
                    "Reads the run back out of the JobRepository, so it works for a job that ran "
                            + "days ago or in another instance of the application.")
    @ApiResponse(responseCode = "200", description = "The execution")
    @ApiResponse(
            responseCode = "404",
            description = "No execution with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public JobExecutionResponse execution(
            @Parameter(description = "JobExecution id", example = "1") @PathVariable long id) {
        return batchService.findExecution(id);
    }

    @PostMapping("/executions/{id}/restart")
    @Operation(
            summary = "Restart a failed import",
            description =
                    "Runs the same import again against the same file. Spring Batch resumes from "
                            + "the last committed chunk rather than from the first row, so the "
                            + "rows already imported are not re-read. Fix the offending rows in "
                            + "place first — leave the line numbering alone, because the saved "
                            + "position is a line count.")
    @ApiResponse(responseCode = "200", description = "The restarted run")
    @ApiResponse(
            responseCode = "404",
            description = "No execution with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "That execution is not a failed product import",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ProductImportResponse restart(
            @Parameter(description = "JobExecution id of the failed run", example = "1")
            @PathVariable long id) {
        return batchService.restartProductImport(id);
    }
}

package com.ecomdemo.catalog.ai;

import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only, and enforced where every other product write is: at the gateway, whose rule for
 * {@code /api/products/**} lets only GETs through anonymously. This service sees the gateway's
 * token, not the admin's (see {@code SecurityConfig}).
 *
 * <p>A POST, not a GET, although no body is sent: it changes the product and it costs money each
 * time. A GET could be prefetched, cached or retried by anything between here and the browser.
 */
@RestController
@RequestMapping("/api/products")
public class ProductDescriptionController {

    private final ProductDescriptionService descriptions;

    public ProductDescriptionController(ProductDescriptionService descriptions) {
        this.descriptions = descriptions;
    }

    @PostMapping("/{id}/generate-description")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Generate a product description with the configured LLM (ADMIN)",
            description = "Writes a description, tags and an SEO title, saves the description to the "
                    + "product, and keeps the whole answer in the generation history.")
    @ApiResponse(responseCode = "200", description = "Generated and saved")
    @ApiResponse(
            responseCode = "404",
            description = "No product with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "No model configured, the model failed or timed out, or its answer was unusable. "
                    + "The product is unchanged; Retry-After says when to try again.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public GeneratedDescriptionResponse generate(
            @Parameter(description = "Id of the product", example = "1") @PathVariable Long id) {
        return descriptions.generate(id);
    }
}

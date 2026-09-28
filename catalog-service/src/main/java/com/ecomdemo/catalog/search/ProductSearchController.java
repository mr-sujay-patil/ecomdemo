package com.ecomdemo.catalog.search;

import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/products/search?q=...} (Phase 28). Public, like browsing: the gateway permits
 * every GET under {@code /api/products}. {@code /search} is a literal path, so Spring prefers it to
 * {@code /{id}} and "search" is never parsed as a product id.
 */
@RestController
@RequestMapping("/api/products")
public class ProductSearchController {

    private final ProductSearchService search;

    public ProductSearchController(ProductSearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    @Operation(
            summary = "Search products by meaning",
            description = "Embeds the query and returns the products nearest to it in meaning, best first, "
                    + "optionally only in one category and price range. Words need not match: \"something to "
                    + "carry my laptop in\" finds a laptop sleeve.")
    @ApiResponse(responseCode = "200", description = "The matches; an empty list if nothing was close enough")
    @ApiResponse(
            responseCode = "400",
            description = "No query, a query over 200 characters, or a bad filter",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "No embedding model configured, or it did not answer; Retry-After says when to try again",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ProductSearchResponse search(
            @Parameter(description = "What the shopper is looking for, in their own words",
                    example = "something to protect my laptop on the train")
            @RequestParam @NotBlank @Size(max = 200) String q,
            @Parameter(description = "Only this category (any case)", example = "ACCESSORIES")
            @RequestParam(required = false) @Size(max = 50) String category,
            @Parameter(description = "Lowest price, inclusive", example = "1000")
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
            @Parameter(description = "Highest price, inclusive", example = "5000")
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
            @Parameter(description = "How many results, at most (default 5, max 20)", example = "5")
            @RequestParam(required = false) @Min(1) @Max(20) Integer limit) {
        return search.search(q, new ProductSearchIndex.Filters(category, minPrice, maxPrice), limit);
    }
}

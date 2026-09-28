package com.ecomdemo.catalog.search;

import com.ecomdemo.catalog.dto.ProductResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/** One search result: the product as the catalogue serves it, and how close it came. */
public record ProductSearchHit(
        ProductResponse product,

        @Schema(description = "Cosine similarity between the query and the product, 1 = identical in meaning.",
                example = "0.71")
        double similarity) {
}

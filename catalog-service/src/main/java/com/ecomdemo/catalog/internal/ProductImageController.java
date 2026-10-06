package com.ecomdemo.catalog.internal;

import com.ecomdemo.catalog.ProductImage;
import com.ecomdemo.catalog.ProductImageService;
import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Serves a product's image (Phase 34). Read-only: there is no upload, images are seed data.
 *
 * <p>Why a URL and not the bytes inside the product JSON: the browser fetches, caches and revalidates
 * each image on its own, a product list does not carry hundreds of kilobytes it did not ask for, and
 * an {@code <img>} cannot send a bearer token, which is why this path is public at the gateway.
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "Product images", description = "A product's picture, for a browser <img>. Public to read.")
public class ProductImageController {

    private final ProductImageService images;

    public ProductImageController(ProductImageService images) {
        this.images = images;
    }

    @GetMapping("/{id}/image")
    @Operation(
            summary = "Get a product's image",
            description = "The image named by the product's imageUrl. Formats: SVG, PNG, WebP, JPEG. No size variants. "
                    + "Cached publicly for a day and revalidated by ETag (If-None-Match gives 304). "
                    + "Sent with X-Content-Type-Options: nosniff and Cross-Origin-Resource-Policy: cross-origin, "
                    + "so a storefront on another origin may embed it; an <img> needs no CORS. SVGs also carry a "
                    + "Content-Security-Policy that forbids everything.")
    @ApiResponse(responseCode = "200", description = "The image",
            content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "304", description = "The image has not changed since the ETag in If-None-Match",
            content = @Content)
    @ApiResponse(responseCode = "404", description = "No such product, or the product has no image",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<byte[]> image(
            @Parameter(description = "Id of the product", example = "1") @PathVariable Long id,
            WebRequest request) {
        ProductImage image = images.find(id);

        // Answers 304 (and sets the ETag header) when the client's If-None-Match matches.
        if (request.checkNotModified(image.etag())) {
            return null;
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(image.mediaType())
                .eTag(image.etag())
                // Public: a shared cache (CDN, the gateway's clients) may keep it. A day, not a year:
                // the URL has no version in it, so a replaced image must reach clients within a bounded time.
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                // Lets a page on another origin (the storefront dev server) embed it. <img> is not subject to CORS.
                .header("Cross-Origin-Resource-Policy", "cross-origin");
        if (image.mediaType().getSubtype().startsWith("svg")) {
            // An SVG opened as a document (not inside <img>) could run script; this makes that inert.
            response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
        return response.body(image.content());
    }
}

package com.ecomdemo.catalog.internal;

import com.ecomdemo.catalog.ProductService;
import com.ecomdemo.shared.ApiError;
import com.ecomdemo.catalog.dto.ProductRequest;
import com.ecomdemo.clients.catalog.ProductSnapshot;
import com.ecomdemo.clients.catalog.ProductUpsert;
import com.ecomdemo.catalog.dto.ProductPage;
import com.ecomdemo.catalog.dto.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for the catalogue. No business logic, no persistence. */
@RestController
@RequestMapping("/api/products")
@Tag(
        name = "Products",
        description =
                "The catalogue. Browsing is open to anyone — it is the shop window. Creating, "
                        + "replacing and deleting require an ADMIN account.")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** Largest page a caller may ask for (KI-007). */
    static final int MAX_PAGE_SIZE = 100;

    @GetMapping
    @Operation(
            summary = "List the products, one page at a time",
            description = "Returns one page of the catalogue as a JSON array, in id order. `page` counts from 0 "
                    + "(default 0) and `size` defaults to 50, at most 100. `X-Total-Count` is the number of "
                    + "products in all, and `Link` carries the first, prev, next and last pages. A page past the "
                    + "end is an empty array.")
    @ApiResponse(
            responseCode = "200",
            description = "The page, possibly empty",
            headers = {
                @Header(name = "X-Total-Count", description = "Products in the whole catalogue"),
                @Header(name = "Link", description = "RFC 8288 links: first, prev, next, last")})
    @ApiResponse(
            responseCode = "400",
            description = "A negative page, or a size under 1 or over 100",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<ProductResponse>> list(
            @Parameter(description = "Page number, from 0", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Products per page (default 50, max 100)", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        ProductPage found = productService.findPage(page, size);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(found.total()))
                .header(HttpHeaders.LINK, links(page, size, found.total()))
                .body(found.items());
    }

    /**
     * RFC 8288 links, relative so they work behind the gateway as they do here. {@code prev} and
     * {@code next} are left out at the ends, and a page past the end links back to the last page.
     */
    private static String links(int page, int size, long total) {
        int last = (int) Math.max(0, (total - 1) / size);
        List<String> links = new ArrayList<>();
        links.add(link(0, size, "first"));
        if (page > 0) {
            links.add(link(Math.min(page - 1, last), size, "prev"));
        }
        if (page < last) {
            links.add(link(page + 1, size, "next"));
        }
        links.add(link(last, size, "last"));
        return String.join(", ", links);
    }

    private static String link(int page, int size, String rel) {
        return "</api/products?page=" + page + "&size=" + size + ">; rel=\"" + rel + "\"";
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one product by id")
    @ApiResponse(responseCode = "200", description = "The product")
    @ApiResponse(
            responseCode = "404",
            description = "No product with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ProductResponse get(
            @Parameter(description = "Id of the product", example = "1") @PathVariable Long id) {
        return productService.findById(id);
    }

    /**
     * Bulk create-or-update, for the CSV import that lives in another service now.
     *
     * <p>Deliberately NOT the single {@code POST} in a loop. A ten-thousand-row import would be ten
     * thousand HTTP round trips, each with its own transaction and its own service token check —
     * and the chunk-oriented import would lose the thing that makes it restartable, which is that a
     * chunk succeeds or fails as a unit.
     *
     * <p>It is under {@code /batch} rather than at the collection root because the root already
     * means "create one and return 201 with a Location". This returns a list and no Location; two
     * different operations should not share a URI and differ only by the shape of the body.
     */
    @PostMapping("/batch")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Create or update many products",
            description = "A null id creates; a set id updates. Used by the CSV import.")
    List<ProductSnapshot> upsertAll(@Valid @RequestBody List<ProductUpsert> products) {
        return productService.upsertAll(products);
    }

    /** 201 with a Location header, as a resource-creating POST should. */
    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Create a product (ADMIN)",
            description = "The id is generated by the server; anything sent for it is ignored.")
    @ApiResponse(
            responseCode = "201",
            description = "Created. The Location header points at the new product.")
    @ApiResponse(
            responseCode = "400",
            description = "A field failed validation, or the body was not readable JSON",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "No Bearer token, or one that is invalid or expired",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated, but not as an ADMIN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Replace a product (ADMIN)",
            description = "A full replacement: every field in the body is written, so omitting one clears it.")
    @ApiResponse(responseCode = "200", description = "The updated product")
    @ApiResponse(
            responseCode = "400",
            description = "A field failed validation, or the body was not readable JSON",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No product with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "No Bearer token, or one that is invalid or expired",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated, but not as an ADMIN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ProductResponse update(
            @Parameter(description = "Id of the product to replace", example = "1") @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Delete a product (ADMIN)",
            description = "Removing a product that is still in the cart is not prevented yet.")
    @ApiResponse(responseCode = "204", description = "Deleted; no body")
    @ApiResponse(
            responseCode = "404",
            description = "No product with that id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "No Bearer token, or one that is invalid or expired",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated, but not as an ADMIN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> delete(
            @Parameter(description = "Id of the product to delete", example = "1") @PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

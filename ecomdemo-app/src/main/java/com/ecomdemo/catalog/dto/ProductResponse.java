package com.ecomdemo.catalog.dto;

import com.ecomdemo.catalog.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * What the API returns for a product.
 *
 * <p>Entities are never returned directly. An entity is tied to a persistence context (lazy
 * proxies can explode during serialisation), and exposing it would make every column part of
 * the public API, so a column rename would silently break clients.
 */
@Schema(name = "ProductResponse", description = "A product as the catalogue returns it.")
public record ProductResponse(
        @Schema(description = "Generated identifier.", example = "1")
        Long id,

        @Schema(example = "Mechanical Keyboard")
        String name,

        @Schema(example = "Hot-swappable switches, PBT keycaps, 87 keys.")
        String description,

        @Schema(description = "Unit price with two decimal places.", example = "8999.00")
        BigDecimal price,

        @Schema(description = "Units currently available. Checkout reduces this.", example = "25")
        int stockQuantity,

        @Schema(description = "Optional catalogue category. Null for products created before "
                + "the category column existed, or created without one.",
                example = "PERIPHERALS", nullable = true)
        String category) {

    /**
     * Builds the response from a product and the stock somebody else supplied.
     *
     * <p><strong>The quantity is a parameter since Phase 20</strong>, because the catalogue no
     * longer holds it — {@code product_stock} is the inventory module's table, and shortly the
     * inventory service's database. The API shape is unchanged, which is deliberate: this is an
     * internal split, and no client should be able to tell that the number now comes from
     * somewhere else.
     *
     * <p>Taking it as a parameter rather than looking it up here is what keeps that lookup
     * BATCHED. A listing of forty products asks inventory once; a version of this method that
     * fetched its own number would ask forty times, which is N+1 today and forty HTTP round trips
     * once the services are apart.
     */
    public static ProductResponse from(Product product, int stockQuantity) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                stockQuantity,
                product.getCategory());
    }
}

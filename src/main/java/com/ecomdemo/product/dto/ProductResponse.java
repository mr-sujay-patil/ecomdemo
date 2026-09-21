package com.ecomdemo.product.dto;

import com.ecomdemo.product.Product;
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
        int stockQuantity) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity());
    }
}

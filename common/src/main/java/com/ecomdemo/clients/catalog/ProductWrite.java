package com.ecomdemo.clients.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A product as a CALLER writes it: the body of the public create and update.
 *
 * <p>It carries {@code stockQuantity} and {@link ProductUpsert} does not, which is the one thing
 * worth explaining. They are two different jobs:
 *
 * <ul>
 *   <li>This one mirrors what the public API has accepted since Phase 1 — a product and how many of
 *       it there are, in one request. catalog-service splits that into its own write and a call to
 *       inventory-service, which is exactly where the split should be absorbed: at the boundary,
 *       not in the shopper's request body.
 *   <li>{@link ProductUpsert} is the CSV import's shape, and the import sets stock through its own
 *       call because it already has to, per row, and because a chunk of ten thousand should not
 *       carry two concerns in one field.
 * </ul>
 *
 * <p>The constraints are repeated here rather than left to catalog-service, so a bad request fails
 * at the edge the caller is actually talking to. The service validates again; a boundary that
 * trusts its caller is not a boundary.
 */
public record ProductWrite(
        @Schema(description = "Display name.", example = "Mechanical Keyboard")
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Schema(description = "Optional long description.", example = "Hot-swappable switches, PBT keycaps.")
        @Size(max = 1000, message = "must be at most 1000 characters")
        String description,

        @Schema(description = "Unit price, at most two decimal places.", example = "8999.00")
        @NotNull(message = "is required")
        // @DecimalMin("0.01"), NOT @PositiveOrZero: a free product is a pricing decision nobody has
        // made, and the constraint is copied exactly from the catalogue's own request record so the
        // edge and the service agree. They must — a body this accepts and catalog-service rejects
        // would surface as a 500 from a perfectly valid-looking request.
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @Digits(integer = 10, fraction = 2, message = "must have at most two decimal places")
        BigDecimal price,

        @Schema(description = "How many are in stock. Applied by inventory-service.", example = "25")
        @NotNull(message = "is required")
        @PositiveOrZero(message = "must not be negative")
        Integer stockQuantity,

        @Schema(description = "Free-text category.", example = "PERIPHERALS")
        @Size(max = 50, message = "must be at most 50 characters")
        String category) {
}

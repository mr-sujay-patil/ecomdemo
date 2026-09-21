package com.ecomdemo.product.dto;

import com.ecomdemo.product.Product;
import java.math.BigDecimal;

/**
 * What the API returns for a product.
 *
 * <p>Entities are never returned directly. An entity is tied to a persistence context (lazy
 * proxies can explode during serialisation), and exposing it would make every column part of
 * the public API, so a column rename would silently break clients.
 */
public record ProductResponse(
        Long id, String name, String description, BigDecimal price, int stockQuantity) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity());
    }
}

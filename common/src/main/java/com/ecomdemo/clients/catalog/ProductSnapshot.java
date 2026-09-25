package com.ecomdemo.clients.catalog;

import java.math.BigDecimal;

/**
 * A product as another service sees it: a value, read once, that stops tracking the catalogue the
 * moment it is returned.
 *
 * <p><strong>{@code stockQuantity} is here but is not the catalogue's.</strong> catalog-service
 * fills it by calling inventory-service, because a product listing that does not say how many there
 * are is not much of a product listing. It travels in this record so the application can serve the
 * public {@code /api/products} without a second call of its own — but nothing should write it, and
 * nothing should decide a sale on it. The number a sale is decided on is read live, inside
 * inventory-service, under a row lock. This one is for display.
 */
public record ProductSnapshot(
        Long id,
        String name,
        String description,
        BigDecimal price,
        String category,
        Integer stockQuantity) {
}

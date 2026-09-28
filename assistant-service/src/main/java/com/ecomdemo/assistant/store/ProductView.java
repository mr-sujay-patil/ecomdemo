package com.ecomdemo.assistant.store;

import java.math.BigDecimal;

/** A product as catalog-service returns it; only the fields the assistant uses. */
public record ProductView(
        Long id, String name, String description, BigDecimal price, int stockQuantity, String category) {
}

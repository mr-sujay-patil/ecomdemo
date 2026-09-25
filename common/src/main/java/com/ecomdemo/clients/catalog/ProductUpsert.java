package com.ecomdemo.clients.catalog;

import java.math.BigDecimal;

/**
 * A product to create or update.
 *
 * <p>{@code id} is null for a create and set for an update, which is exactly the distinction the
 * CSV import already made when it held an entity with a null id. What changed is that the decision
 * is now explicit in a value rather than implicit in whether Hibernate considers the object
 * transient.
 */
public record ProductUpsert(Long id, String name, String description, BigDecimal price, String category) {
}

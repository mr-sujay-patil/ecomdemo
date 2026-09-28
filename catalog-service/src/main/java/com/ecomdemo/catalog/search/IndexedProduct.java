package com.ecomdemo.catalog.search;

import com.ecomdemo.catalog.Product;
import java.math.BigDecimal;

/**
 * The part of a product the search index holds: what is embedded (name, description, category) and
 * what is filtered on (category, price). A value, so the batch backfill can build it from a row
 * without loading an entity, and the Kafka indexer from an entity.
 */
public record IndexedProduct(Long id, String name, String description, String category, BigDecimal price) {

    public static IndexedProduct from(Product product) {
        return new IndexedProduct(product.getId(), product.getName(), product.getDescription(),
                product.getCategory(), product.getPrice());
    }
}

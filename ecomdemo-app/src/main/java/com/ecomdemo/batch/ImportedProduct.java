package com.ecomdemo.batch;

import com.ecomdemo.clients.catalog.ProductUpsert;

/**
 * One CSV row, validated and split into the two writes it becomes.
 *
 * <p>It used to hold a {@code Product} ENTITY. It cannot now: the entity belongs to
 * catalog-service's persistence context and catalog-service's database, so what travels between
 * the processor and the writer is a value. The pairing with {@code stockQuantity} is unchanged and
 * is the honest shape of the problem — one row of a CSV is two facts owned by two services.
 */
record ImportedProduct(ProductUpsert product, int stockQuantity) {}

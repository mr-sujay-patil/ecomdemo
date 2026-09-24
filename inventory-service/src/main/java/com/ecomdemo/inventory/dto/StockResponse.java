package com.ecomdemo.inventory.dto;

/**
 * How many of one product there are.
 *
 * @param productId the product asked about
 * @param quantity what is available; ZERO for a product with no stock row, which is a real answer
 *     rather than a 404 — see {@code InventoryService}
 */
public record StockResponse(Long productId, int quantity) {}

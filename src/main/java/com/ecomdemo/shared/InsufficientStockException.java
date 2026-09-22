package com.ecomdemo.shared;

/** Thrown when an order asks for more units than are in stock. Mapped to HTTP 409. */
public class InsufficientStockException extends ConflictException {

    public InsufficientStockException(String productName, int requested, int available) {
        super("Insufficient stock for '%s': requested %d, available %d"
                .formatted(productName, requested, available));
    }
}

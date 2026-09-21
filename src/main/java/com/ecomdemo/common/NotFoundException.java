package com.ecomdemo.common;

/** Thrown when a requested entity does not exist. Mapped to HTTP 404 by {@link GlobalExceptionHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException product(Long id) {
        return new NotFoundException("Product %d not found".formatted(id));
    }

    public static NotFoundException order(Long id) {
        return new NotFoundException("Order %d not found".formatted(id));
    }

    public static NotFoundException cartItem(Long productId) {
        return new NotFoundException("Product %d is not in the cart".formatted(productId));
    }
}

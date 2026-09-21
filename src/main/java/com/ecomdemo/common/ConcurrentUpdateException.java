package com.ecomdemo.common;

/**
 * Thrown when an operation kept losing an optimistic lock and ran out of retries. Mapped to
 * HTTP 409 by {@link GlobalExceptionHandler}, the same as any other conflict with current state:
 * nothing is broken, the request simply collided with another one and can be repeated.
 */
public class ConcurrentUpdateException extends ConflictException {

    public ConcurrentUpdateException(String message) {
        super(message);
    }
}

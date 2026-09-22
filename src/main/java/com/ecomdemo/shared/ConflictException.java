package com.ecomdemo.shared;

import com.ecomdemo.shared.internal.GlobalExceptionHandler;

/**
 * Thrown when a request is well-formed but conflicts with the current state of the system,
 * for example ordering an empty cart. Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

package com.ecomdemo.shared;

import com.ecomdemo.shared.internal.GlobalExceptionHandler;
import java.time.Duration;

/**
 * Thrown when a service this one depends on cannot answer, and the request cannot be served without
 * it. Mapped to HTTP 503 with a {@code Retry-After} header by {@link GlobalExceptionHandler}.
 *
 * <p><strong>Why 503 and not 500.</strong> A 500 says "this server has a bug"; a 503 says "this
 * server is fine, something it needs is not, and it is worth asking again later". Clients,
 * load balancers and retry libraries treat the two differently, and only one of them is true.
 *
 * <p>{@link #retryAfter()} is a hint, not a promise. For an open circuit it is how long the breaker
 * stays open, so a well-behaved client stops asking for exactly as long as asking is pointless.
 */
public class ServiceUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public ServiceUnavailableException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

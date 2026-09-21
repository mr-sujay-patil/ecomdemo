package com.ecomdemo.common;

/**
 * The single error shape every failing request returns.
 *
 * <p>A record is enough: an error response is immutable data, it needs no behaviour, and
 * Jackson serialises the components straight to {@code {"status": ..., "message": ...}}.
 */
public record ApiError(int status, String message) {
}

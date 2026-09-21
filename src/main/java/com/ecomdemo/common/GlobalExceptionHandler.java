package com.ecomdemo.common;

import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Turns exceptions thrown anywhere below a controller into the {@link ApiError} shape.
 *
 * <p>Without this, Spring Boot's default error page leaks timestamps, paths and exception class
 * names, and every controller would need its own try/catch. Centralising it here keeps the
 * controllers free of error handling and guarantees one consistent contract for clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 404: the entity does not exist. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 409: the request is valid but conflicts with current state (empty cart, not enough stock). */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 409: an optimistic lock was lost and nothing retried it.
     *
     * <p>Checkout never reaches here — {@code OrderService} retries and then throws
     * {@link ConcurrentUpdateException} — but any other versioned write (editing a product while
     * an order is reducing its stock, for instance) would otherwise surface as a 500. It is not
     * a server error: the request was fine, it simply lost a race, and 409 tells the client that
     * repeating it is worth a try. Without this the stack trace of a Hibernate internal would be
     * the client's only clue.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return build(
                HttpStatus.CONFLICT,
                "The record was changed by another request while this one was in flight. "
                        + "Please re-read it and try again.");
    }

    /** 400: Bean Validation rejected the request body (@Valid on a @RequestBody record). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message.isEmpty() ? "Validation failed" : message);
    }

    /** 400: Bean Validation rejected a path variable or request parameter. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(HandlerMethodValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "Validation failed");
    }

    /** 400: the body was not readable at all (malformed JSON, wrong type for a field). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), message));
    }
}

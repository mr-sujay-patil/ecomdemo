package com.ecomdemo.common;

import java.util.stream.Collectors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
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

    /**
     * Tells an anonymous authentication apart from a real one. Spring Security always puts
     * <em>something</em> in the context — an {@code AnonymousAuthenticationToken} when nobody
     * has logged in — so a null check alone is not enough.
     */
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

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

    /**
     * 403 (or 401): method security refused the call.
     *
     * <p>Two different things produce an {@code AccessDeniedException} and they are answered in
     * two different places. A URL rule in {@code SecurityConfig} is evaluated in the filter
     * chain, before Spring MVC exists, and is answered by {@code ApiErrorAccessDeniedHandler}.
     * A {@code @PreAuthorize} or {@code @PostAuthorize} is evaluated much later, on a proxy
     * around a service that a controller has already called — so the exception travels back up
     * through the handler method and lands here, where {@code @RestControllerAdvice} can see it.
     * Both paths produce the same body; only the shapes of the two are worth remembering.
     *
     * <p>The status is not unconditionally 403. Spring Security's filter chain answers 401 when
     * an <em>anonymous</em> caller is denied, because the denial might simply be a missing
     * login, and that distinction has to be preserved here too — otherwise a client with no
     * credentials would be told "forbidden" and have nothing to retry with. Every method-secured
     * path in this application is already behind an {@code authenticated()} URL rule, so the 401
     * branch should be unreachable; it exists so that a future endpoint that is not stays
     * correct.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || trustResolver.isAnonymous(authentication)) {
            return build(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required. Send HTTP Basic credentials with this request.");
        }
        return build(
                HttpStatus.FORBIDDEN, "Your account does not have permission to perform this action.");
    }

    /**
     * 401: the login endpoint refused the credentials.
     *
     * <p>This one really does reach the advice. {@code /api/auth/login} is a permitted URL, so
     * the filter chain lets the request through to the controller, and the failure happens
     * inside {@code AuthService} when the {@code AuthenticationManager} rejects it — below a
     * controller, where {@code @RestControllerAdvice} can see it. The filter chain's own 401s,
     * by contrast, never get this far.
     *
     * <p>{@code ex.getMessage()} is deliberately not used. Spring Security distinguishes
     * {@code UsernameNotFoundException} from {@code BadCredentialsException} internally for its
     * own logs; passing either message to the client would turn this endpoint into a way of
     * discovering which usernames exist. One sentence for every failure.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleFailedLogin(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid username or password");
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

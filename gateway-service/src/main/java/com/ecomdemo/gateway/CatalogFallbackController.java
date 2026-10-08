package com.ecomdemo.gateway;

import com.ecomdemo.shared.ApiError;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a shopper is told when the catalogue read route cannot be served (KI-004): the route's
 * {@code CircuitBreaker} filter forwards here when catalog-service times out, is unreachable,
 * answers 502/503/504, or the breaker is open and the call is refused without being made.
 *
 * <p>The same {@link ApiError} body and {@code Retry-After} header the services themselves use for
 * "temporarily unavailable", so a client has one failure to understand, not one per layer. Without
 * this, the gateway's own answers differ by cause: a refused connection is a bare 503, a timeout a
 * 504, an open breaker a 503 with an empty body.
 *
 * <p>Reached only by an internal forward. An external request for this path carries no token and is
 * refused by the security chain like any other unknown path.
 */
@RestController
class CatalogFallbackController {

    /** The breaker stays open for 10 s (see {@code GatewayResilienceConfig}): the honest "try again". */
    static final Duration RETRY_AFTER = Duration.ofSeconds(10);

    @RequestMapping("/fallback/catalog")
    ResponseEntity<ApiError> catalogUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER.toSeconds()))
                .body(new ApiError(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "The product catalogue is temporarily unavailable. Please try again shortly."));
    }
}

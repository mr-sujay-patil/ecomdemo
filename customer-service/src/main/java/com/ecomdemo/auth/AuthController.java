package com.ecomdemo.auth;

import com.ecomdemo.auth.throttle.LoginThrottledException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exchanging credentials for a token. */
@RestController
@RequestMapping("/api/auth")
@Tag(
        name = "Authentication",
        description =
                "Log in once, then send the returned token as `Authorization: Bearer <token>` on "
                        + "every other call. This is the only endpoint that accepts a password.")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Log in and receive a token",
            description =
                    """
                    Verifies the password with BCrypt — the only time per session that cost is \
                    paid — and returns a signed JWT carrying the account's id and roles.

                    The token is short-lived and cannot be revoked before it expires, so treat it \
                    like a password: whoever holds it is the account, which is what "Bearer" means.
                    """)
    @ApiResponse(responseCode = "200", description = "The token, its type and when it expires")
    @ApiResponse(
            responseCode = "400",
            description = "username or password is missing",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description =
                    "The credentials are wrong. The message deliberately does not say whether it "
                            + "was the username or the password.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Too many failed logins for this username or from this address. Wait for "
                    + "the number of seconds in the Retry-After header, then try again.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, clientAddress(http));
    }

    /**
     * 429 with {@code Retry-After}, so a client can wait exactly as long as it must (Phase 33). The
     * message gives the same number for a person reading it.
     */
    @ExceptionHandler(LoginThrottledException.class)
    ResponseEntity<ApiError> throttled(LoginThrottledException ex) {
        long seconds = ex.retryAfterSeconds();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new ApiError(HttpStatus.TOO_MANY_REQUESTS.value(),
                        "Too many failed logins. Try again in %d seconds.".formatted(seconds)));
    }

    /**
     * The address the per-client throttle counts against: the LAST {@code X-Forwarded-For} entry.
     *
     * <p>Behind the gateway every request comes from the gateway's own address, which would put all
     * users in one bucket. The gateway appends the real client address to {@code X-Forwarded-For};
     * earlier entries are whatever the client sent, and a client can send anything. So only the
     * last hop, added by our own gateway, is used. A caller that reaches this service directly (its
     * port is published in compose, KI-003) can still forge it; in Kubernetes the port is internal.
     */
    static String clientAddress(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return http.getRemoteAddr();
    }
}

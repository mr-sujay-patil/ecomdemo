package com.ecomdemo.auth;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.common.ApiError;
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
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}

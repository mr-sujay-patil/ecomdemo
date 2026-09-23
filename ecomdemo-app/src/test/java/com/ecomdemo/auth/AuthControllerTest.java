package com.ecomdemo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.support.WithSecurityRules;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web-slice tests for {@link AuthController}.
 *
 * <p>Anonymous by default, because that is the whole point of this endpoint: you cannot present
 * a token in order to get a token.
 */
@WebMvcTest(AuthController.class)
@WithSecurityRules
@WithAnonymousUser
class AuthControllerTest {

    private static final String VALID_BODY =
            """
            {"username":"asha","password":"correct-horse-battery-staple"}
            """;

    private static final TokenResponse TOKEN = TokenResponse.bearer(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhc2hhIn0.signature",
            Instant.parse("2026-09-22T09:00:00Z"),
            Instant.parse("2026-09-22T09:15:00Z"));

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("login is reachable with no credentials at all, and returns a token")
    void login_whenCredentialsAreValid_returns200WithAToken() {
        // Given
        when(authService.login(any(LoginRequest.class))).thenReturn(TOKEN);

        // When / Then
        assertThat(mvc.post().uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .hasStatus(OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"accessToken":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhc2hhIn0.signature",
                         "tokenType":"Bearer","expiresIn":900,
                         "expiresAt":"2026-09-22T09:15:00Z"}
                        """);
    }

    @Test
    @DisplayName("the response never echoes the password")
    void login_whenSuccessful_neverEchoesThePassword() throws Exception {
        // Given
        when(authService.login(any(LoginRequest.class))).thenReturn(TOKEN);

        // When
        String body = mvc.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .exchange()
                .getResponse()
                .getContentAsString();

        // Then
        assertThat(body).doesNotContain("correct-horse-battery-staple").doesNotContain("password");
    }

    @Test
    @DisplayName("wrong credentials are a 401 in the standard error shape")
    void login_whenCredentialsAreWrong_returns401() {
        // Given
        when(authService.login(any(LoginRequest.class))).thenThrow(new BadCredentialsException("Bad credentials"));

        // When / Then: the message deliberately does not say which half was wrong. Spring
        // Security's own wording is dropped for the same reason.
        assertThat(mvc.post().uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .hasStatus(UNAUTHORIZED)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status":401,"message":"Invalid username or password"}
                        """);
    }

    @Test
    @DisplayName("an unknown username gets the identical 401, so accounts cannot be enumerated")
    void login_whenTheAccountDoesNotExist_returnsTheSameBody() {
        // Given
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException(
                        "No account named nobody"));

        // When / Then
        assertThat(mvc.post().uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"nobody","password":"correct-horse-battery-staple"}"""))
                .hasStatus(UNAUTHORIZED)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status":401,"message":"Invalid username or password"}
                        """);
    }

    @Test
    @DisplayName("a missing field is a 400, and never reaches the service")
    void login_whenAFieldIsMissing_returns400() {
        assertThat(mvc.post().uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"asha"}"""))
                .hasStatus(BAD_REQUEST);
        verify(authService, never()).login(any());
    }
}

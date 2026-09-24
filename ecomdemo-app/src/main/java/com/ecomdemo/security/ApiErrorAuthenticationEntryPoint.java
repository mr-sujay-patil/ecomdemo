package com.ecomdemo.security;

import com.ecomdemo.shared.AuthMessages;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401: the request needs a user and did not have one, or the credentials were wrong.
 *
 * <p>An {@code AuthenticationEntryPoint} is what Spring Security invokes when it decides the
 * caller has to authenticate. "Entry point" is the name for it because in a browser application
 * this is where the login page would be served; for an API there is no page to send, so it
 * answers with the error instead.
 *
 * <p>It answers two different situations, and says which:
 *
 * <ul>
 *   <li><strong>No token at all.</strong> The request never identified itself, so the caller is
 *       told to log in.</li>
 *   <li><strong>A token that did not survive verification</strong> — a wrong or missing
 *       signature, an {@code exp} in the past, an issuer we did not mint. Spring Security raises
 *       an {@link OAuth2AuthenticationException} for all of these, and the caller is told to log
 *       in again rather than to log in.</li>
 * </ul>
 *
 * <p>The distinction is safe to make because it says nothing about anybody's account: it is
 * about the bytes that were presented. Login failures are the opposite case — "no such user" and
 * "wrong password" come back identical, because telling those apart would let an attacker
 * enumerate valid usernames without guessing a single password.
 *
 * <p><strong>No {@code WWW-Authenticate} header.</strong> Spring Security's defaults send one —
 * {@code Basic realm="Realm"} before Phase 9, {@code Bearer error="invalid_token"} after it. The
 * Basic form makes a browser pop up its native login dialog, which cannot be styled, cancelled
 * cleanly or logged out of. This is a JSON API whose clients are curl, Swagger UI and the smoke
 * test; none of them read the header, and the reason is in the body instead.
 */
@Component
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter writer;

    public ApiErrorAuthenticationEntryPoint(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        boolean tokenWasRejected = authException instanceof OAuth2AuthenticationException;
        writer.write(
                response,
                HttpStatus.UNAUTHORIZED,
                tokenWasRejected ? AuthMessages.INVALID_TOKEN : AuthMessages.NO_TOKEN);
    }
}

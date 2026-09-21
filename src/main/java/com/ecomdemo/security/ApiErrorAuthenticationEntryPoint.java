package com.ecomdemo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
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
 * <p>The message deliberately does not say whether the username existed. "No such user" and
 * "wrong password" both come out as the same sentence, because telling them apart would let an
 * attacker enumerate valid usernames without guessing a single password.
 *
 * <p><strong>No {@code WWW-Authenticate} header.</strong> Spring Security's Basic-auth default
 * sends {@code WWW-Authenticate: Basic realm="Realm"}, which is correct HTTP and makes a browser
 * pop up its native login dialog — a dialog that cannot be styled, cancelled cleanly or logged
 * out of. This is a JSON API whose clients are curl, Swagger UI and the smoke test, none of
 * which need the header, so it is left off.
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
        writer.write(
                response,
                HttpStatus.UNAUTHORIZED,
                "Authentication required. Send HTTP Basic credentials with this request.");
    }
}

package com.ecomdemo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 403: we know who you are, and you still may not do this.
 *
 * <p>The distinction from 401 is the distinction between authentication and authorization, and
 * it is worth being precise about because the two get muddled constantly:
 *
 * <ul>
 *   <li><strong>401 Unauthorized</strong> — despite the name, this means <em>unauthenticated</em>.
 *       The server does not know who you are. Sending credentials could change the answer.</li>
 *   <li><strong>403 Forbidden</strong> — the server knows exactly who you are and the answer is
 *       still no. Sending the same credentials again will never help; only a change of role
 *       would.</li>
 * </ul>
 *
 * <p>Getting this right matters to clients: a 401 tells a client to log in or refresh a token,
 * while a 403 tells it to stop and show the user an explanation. Answering 403 to an anonymous
 * caller would send them into a retry loop with no credentials to retry with.
 */
@Component
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter writer;

    public ApiErrorAccessDeniedHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        writer.write(
                response,
                HttpStatus.FORBIDDEN,
                "Your account does not have permission to perform this action.");
    }
}

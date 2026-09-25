package com.ecomdemo.jwt;

import com.ecomdemo.shared.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an {@link ApiError} straight onto the servlet response.
 *
 * <p>This exists because of <em>where</em> 401 and 403 are decided. Every other error in this
 * application is thrown below a controller and converted by {@code GlobalExceptionHandler} —
 * but {@code @RestControllerAdvice} is part of Spring MVC, and a request rejected by the
 * security filter chain never reaches Spring MVC at all. There is no controller, no handler
 * method and no advice to run; there is only a {@code HttpServletResponse} that somebody has to
 * fill in. That somebody is the {@code AuthenticationEntryPoint} and the
 * {@code AccessDeniedHandler}, and this class is the bit of plumbing they share.
 *
 * <p>Without it, Spring Security's defaults answer with an empty body (and, for Basic
 * authentication, a {@code WWW-Authenticate} header), so a client would get a bare 401 where
 * every other failure in the API is a {@code {"status": ..., "message": ...}} object. One error
 * shape for every failure is the contract; these two statuses are not allowed to be exceptions
 * to it.
 *
 * <p>The injected mapper is {@code tools.jackson.databind.json.JsonMapper} — Jackson 3, the
 * mapper Spring Boot 4 auto-configures and the one Spring MVC serialises every other response
 * with. Jackson 2's {@code com.fasterxml.jackson.databind.ObjectMapper} is still on the
 * classpath (other libraries pull it in) but no bean of that type exists, and using it here
 * would mean these two responses were formatted by a different mapper from all the rest.
 */
@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getWriter(), new ApiError(status.value(), message));
    }
}

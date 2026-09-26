package com.ecomdemo.gateway;

import com.ecomdemo.shared.ApiError;
import com.ecomdemo.shared.AuthMessages;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The edge's failure bodies, in the same shape as every service's.
 *
 * <p><strong>This class exists because of a defect, not a preference.</strong> Phase 20d found that
 * catalog-service and customer-service had never set the resource server's own
 * {@code authenticationEntryPoint}, so a tampered or expired token came back {@code 401} with an
 * <em>empty body</em> instead of an {@link ApiError}. The application's chain carries a comment
 * warning about exactly that, written in Phase 9 — and a comment on one class does not configure
 * another.
 *
 * <p>Moving authentication to the edge is precisely the moment that defect would reappear, one layer
 * up and for every endpoint at once. So the handlers are written here, in the same commit as the
 * chain that needs them, and asserted on by {@code GatewayApiErrorTest}.
 *
 * <p>The wording comes from {@code AuthMessages} rather than from new string literals. Clients have
 * been reading those two sentences since Phase 9; the owner of the 401 changing from the application
 * to the gateway is not a reason for the text to change, and a reworded message is a user-facing
 * change made by accident.
 */
@Component
class ApiErrors implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private static final String FORBIDDEN_MESSAGE =
            "Your account does not have permission to perform this action.";

    private final JsonMapper jsonMapper;

    ApiErrors(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * No credentials, or credentials that could not be trusted.
     *
     * <p>The two cases are told apart the same way the servlet entry point tells them apart: an
     * {@link OAuth2AuthenticationException} means a token WAS presented and was rejected, which
     * deserves "log in again" rather than "log in", because the caller has already tried.
     */
    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        boolean tokenWasRejected = exception instanceof OAuth2AuthenticationException;
        return write(
                exchange,
                HttpStatus.UNAUTHORIZED,
                tokenWasRejected ? AuthMessages.INVALID_TOKEN : AuthMessages.NO_TOKEN);
    }

    /** Authenticated, but not permitted — a customer reaching for an administrator's endpoint. */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return write(exchange, HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = jsonMapper.writeValueAsBytes(new ApiError(status.value(), message));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}

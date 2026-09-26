package com.ecomdemo.gateway;

import com.ecomdemo.clients.ServiceIdentityConfig;
import com.ecomdemo.jwt.JwtKeyConfig;
import com.ecomdemo.metrics.MetricsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * The single door clients knock on.
 *
 * <p><strong>Why the component scan is narrowed.</strong> Every other service in this reactor puts
 * its application class at {@code com.ecomdemo} and lets Boot scan everything below it, including
 * {@code ecomdemo-common}. This one must not. {@code common} holds servlet code — a
 * {@code OncePerRequestFilter}, a {@code @RestControllerAdvice}, an {@code AuthenticationEntryPoint}
 * against {@code HttpServletResponse} — and this application is reactive. Those beans would either
 * fail to load or, worse, load and quietly never run, which is the more expensive failure because
 * everything looks configured.
 *
 * <p>So the scan covers this package only, and the pieces of {@code common} that are genuinely
 * shared are brought in explicitly: {@link JwtKeyConfig} and {@link MetricsConfig} below, plus the
 * constants ({@code AuthMessages}, {@code CorrelationId}, {@code TokenClaims}, {@code ApiError})
 * which are plain classes and need no context at all.
 *
 * <p>{@link ServiceIdentityConfig} supplies the {@code ServiceTokenProvider} that
 * {@link ServiceIdentityFilter} signs with. Its own javadoc explains why it lives in
 * {@code com.ecomdemo.clients} rather than {@code com.ecomdemo.jwt}: everyone verifies, only callers
 * sign — and a gateway is a caller.
 *
 * <p>{@link MetricsConfig} is the one an explicit import is easiest to forget, and forgetting it is
 * invisible: it stamps {@code application=<name>} onto every meter, and without it this service's
 * {@code http_server_requests} series merges with another service's under the same name. Prometheus
 * would show one graph that looks plausible and is two services added together.
 *
 * <p><strong>Why {@link JwtKeyConfig} is imported rather than copied.</strong> It derives the HS256
 * key from {@code JWT_SECRET}, refuses a key shorter than 256 bits instead of padding it, and warns
 * that a generated key differs per service. That is security-relevant logic with a reason behind
 * every line, and a second copy of it at the edge would be a second thing to get wrong. Importing
 * it also contributes a servlet {@code JwtDecoder} bean that nothing here uses — an accepted,
 * visible cost, and cheaper than duplicating key handling. The reactive decoder this application
 * actually validates with is in {@link GatewayJwtConfig}.
 */
@SpringBootApplication(scanBasePackages = "com.ecomdemo.gateway")
@Import({JwtKeyConfig.class, MetricsConfig.class, ServiceIdentityConfig.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

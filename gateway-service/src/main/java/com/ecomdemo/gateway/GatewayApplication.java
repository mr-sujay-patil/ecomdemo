package com.ecomdemo.gateway;

import com.ecomdemo.clients.ServiceIdentityConfig;
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
 * shared are brought in explicitly: {@link MetricsConfig} and {@link ServiceIdentityConfig} below, plus the
 * constants ({@code AuthMessages}, {@code CorrelationId}, {@code TokenClaims}, {@code ApiError})
 * which are plain classes and need no context at all.
 *
 * <p>{@link ServiceIdentityConfig} supplies the {@code ServiceTokenProvider} that
 * {@link ServiceIdentityFilter} uses: since Phase 33 it fetches the gateway's own token from
 * customer-service, scoped to {@code catalog:read}. Everyone verifies, only callers need a token,
 * and a gateway is a caller.
 *
 * <p>{@link MetricsConfig} is the one an explicit import is easiest to forget, and forgetting it is
 * invisible: it stamps {@code application=<name>} onto every meter, and without it this service's
 * {@code http_server_requests} series merges with another service's under the same name. Prometheus
 * would show one graph that looks plausible and is two services added together.
 *
 * <p><strong>Token validation</strong> is in {@link GatewayJwtConfig}: a reactive decoder that checks
 * signatures against customer-service's published PUBLIC keys (Phase 33). Until then this class
 * imported {@code JwtKeyConfig} for the shared HS256 secret; with no shared secret left, there is
 * nothing to import.
 */
@SpringBootApplication(scanBasePackages = "com.ecomdemo.gateway")
@Import({MetricsConfig.class, ServiceIdentityConfig.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

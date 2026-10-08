package com.ecomdemo.gateway;

import java.net.InetSocketAddress;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * KI-047. Keeps the management port (KI-006) to operations: anything that is not under
 * {@code /actuator} answers 404 there.
 *
 * <p><strong>Why a filter is needed at all.</strong> The management server is a second, child web
 * server that reuses the gateway's handler mappings, so {@code :8088/api/products} was routed to the
 * catalogue exactly like {@code :8080/api/products}. The security rules are the same on both, so no
 * privilege was gained, but a port meant for Prometheus and probes should not be an API door.
 *
 * <p><strong>How it knows which port a request came in on.</strong> Spring Boot publishes the real
 * management port as {@code local.management.port} once that server is up (it is also right when the
 * configured port is {@code 0}, as in tests). It is absent when the actuator shares the public port,
 * and then this filter does nothing. It runs first, ahead of the correlation filter and Spring
 * Security, so a refused request costs no authentication and reaches no route or rate limiter.
 */
@Component
class ManagementPortGuardFilter implements WebFilter, Ordered {

    private static final String MANAGEMENT_PATH = "/actuator";

    private final Environment environment;

    ManagementPortGuardFilter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (arrivedOnManagementPort(exchange) && !isActuatorPath(exchange.getRequest().getPath().value())) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean arrivedOnManagementPort(ServerWebExchange exchange) {
        Integer managementPort = environment.getProperty("local.management.port", Integer.class);
        InetSocketAddress local = exchange.getRequest().getLocalAddress();
        return managementPort != null && local != null && local.getPort() == managementPort;
    }

    private static boolean isActuatorPath(String path) {
        return path.equals(MANAGEMENT_PATH) || path.startsWith(MANAGEMENT_PATH + "/");
    }
}

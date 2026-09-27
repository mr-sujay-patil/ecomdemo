package com.ecomdemo.gateway;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * The gateway's copy of common's {@code TracingConfig}: health checks and scrapes are not traced.
 *
 * <p>A copy rather than a reuse because the context type is the stack's own. WebFlux's
 * {@code ServerRequestObservationContext} lives in {@code org.springframework.http.server.reactive
 * .observation} and carries a {@code ServerHttpRequest}; Spring MVC's has the same simple name, a
 * different package and a servlet request. The rule is one line; the contract it shares with the
 * servlet services is the {@code /actuator} prefix, which is Boot's default on both.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig {

    static final String ACTUATOR_PATH = "/actuator";

    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getPath().value().startsWith(ACTUATOR_PATH));
    }
}

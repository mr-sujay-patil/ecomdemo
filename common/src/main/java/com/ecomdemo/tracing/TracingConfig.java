package com.ecomdemo.tracing;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps the machinery's own traffic out of Tempo.
 *
 * <p>Every request a service answers is an observation, and with tracing on every observation
 * with no parent starts a trace. Most requests on the compose network are not a shopper's: Docker
 * asks each service for {@code /actuator/health/readiness} every ten seconds and Prometheus
 * scrapes {@code /actuator/prometheus} every fifteen. At a sampling rate of 1.0 that was a steady
 * stream of one-span traces burying the dozen that matter, measured in Tempo's search before this
 * class existed.
 *
 * <p>An {@link ObservationPredicate} bean is Boot's hook for this: the registry asks every
 * predicate before starting an observation, and one {@code false} turns it into a no-op. No
 * observation means no span AND no metric, which is also right here: the {@code
 * http.server.requests} metric for the scrape endpoint measured Prometheus, not the shop.
 *
 * <p>SERVLET ONLY. The type below is Spring MVC's; the gateway runs on WebFlux, whose context is a
 * different class with the same name, so it has its own copy of this rule. The condition is
 * evaluated from the annotation metadata before the class is loaded, which is what makes it safe
 * to keep a servlet type in a library the reactive gateway also depends on.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class TracingConfig {

    static final String ACTUATOR_PATH = "/actuator";

    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith(ACTUATOR_PATH));
    }
}

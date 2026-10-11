package com.ecomdemo.gateway;

import com.ecomdemo.logging.CorrelationId;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
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

    /**
     * KI-035: the request's correlation id on the gateway's server span, as the attribute
     * {@code correlation_id}, so the id a user was shown finds the trace in Tempo with
     * {@code { span.correlation_id = "<id>" }}.
     *
     * <p><strong>Why an {@link ObservationFilter}.</strong> The server observation is started by the
     * WebFlux adapter BEFORE any WebFilter runs, so when it starts the id does not exist yet. A filter
     * is applied when the observation STOPS, just before the span is ended and exported, and by then
     * {@code CorrelationIdWebFilter} has long put the id in the exchange's attributes, which the
     * context shares ({@code ServerRequestObservationContext#getAttributes}).
     *
     * <p><strong>High cardinality, on purpose.</strong> Every request has its own id. A high-cardinality
     * key value goes on the span only; a low-cardinality one would also become a Prometheus label on
     * {@code http_server_requests}, one time series per request, which is how a metrics backend is
     * brought down.
     *
     * <p><strong>Not baggage.</strong> Baggage is propagated to every downstream call as a header and
     * copied onto their spans: the services already receive the id as {@code X-Correlation-Id} and log
     * it, and they are servlet applications whose own spans would need the same tagging to benefit. The
     * question this answers is narrower: "which trace is the request the gateway answered itself?", and
     * the gateway's server span is the root of that trace.
     */
    @Bean
    ObservationFilter correlationIdOnServerSpans() {
        return context -> {
            if (context instanceof ServerRequestObservationContext server
                    && server.getAttributes().get(CorrelationIdWebFilter.ATTRIBUTE) instanceof String id) {
                context.addHighCardinalityKeyValue(KeyValue.of(CorrelationId.MDC_KEY, id));
            }
            return context;
        };
    }
}

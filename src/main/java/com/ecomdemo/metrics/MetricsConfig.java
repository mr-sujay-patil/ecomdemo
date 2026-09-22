package com.ecomdemo.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registry-wide rules: what every meter is tagged with, and what is not worth measuring.
 *
 * <p>Both beans here are {@link MeterRegistryCustomizer}s, which Spring Boot applies to the
 * registry <em>before</em> any auto-configured meter is bound to it. That ordering is the whole
 * reason this is a customizer rather than a {@code @PostConstruct} somewhere: a filter added
 * after a meter is registered does not apply to it, so half the JVM meters would be tagged and
 * half would not, and the two halves would be different time series with the same name.
 */
@Configuration
public class MetricsConfig {

    private final String applicationName;

    public MetricsConfig(@Value("${ecomdemo.metrics.application}") String applicationName) {
        this.applicationName = applicationName;
    }

    /**
     * Stamps {@code application=<name>} onto every meter in the registry.
     *
     * <p>With one application this looks like ceremony. It stops looking like ceremony the first
     * time a second one publishes a meter called {@code http_server_requests_seconds_count} into
     * the same Prometheus, which is the normal end state of this project — the instance and job
     * labels Prometheus adds at scrape time describe where it scraped, not what the thing is, and
     * they change the moment a container is rescheduled. A tag carried by the application is
     * stable across redeployment, so a dashboard filtered on it keeps working.
     *
     * <p>A common tag is also the cheapest possible way to get this right, because it is applied
     * once here instead of being remembered at every call site.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("application", applicationName);
    }

    /**
     * Drops the per-endpoint HTTP timers for Actuator's own endpoints.
     *
     * <p>Prometheus scrapes {@code /actuator/prometheus} every fifteen seconds, and without this
     * filter each scrape is itself recorded as an HTTP request — so the busiest endpoint in the
     * application is the one that reports how busy the application is. That is not just noise on
     * a graph: with the percentile histogram enabled for {@code http.server.requests}, the
     * scrape's own URI carries a full set of bucket series that nobody will ever query.
     *
     * <p>{@code deny} here removes the meter entirely rather than merely hiding it from the
     * dashboard, which means the memory it would have occupied is never allocated. The health
     * endpoint is filtered for the same reason — the container probes it every ten seconds.
     *
     * <p>Note what is <em>not</em> filtered: the JVM, pool and cache meters. A meter filter is
     * for cardinality and noise, not for making a dashboard tidier; anything dropped here is
     * unavailable during the incident when somebody finally wants it.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> ignoreActuatorRequests() {
        return registry -> registry.config()
                .meterFilter(MeterFilter.deny(id -> {
                    String uri = id.getTag("uri");
                    return uri != null && uri.startsWith("/actuator");
                }));
    }
}

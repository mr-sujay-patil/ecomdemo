package com.ecomdemo.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The circuit breaker on the catalogue read route (KI-004), configured in code: one place, and no
 * dependence on property binding from the Resilience4j Boot module that Spring Cloud CircuitBreaker
 * brings in transitively here (the Boot 3 build, 2.3.0, unlike the Boot 4 module the application
 * uses). Not checked whether that module's properties would bind on Boot 4; the code does not need them.
 *
 * <p>The same numbers as the application's breaker around catalog-service (Phase 22): judge the last
 * ten calls but not before five, open at 50% failures, stay open ten seconds, then let three trial
 * calls decide. See {@code application.properties} in ecomdemo-app for why each is what it is.
 *
 * <p><strong>The time limiter is a backstop, not the deadline.</strong> Spring Cloud's default time
 * limiter is one second, and it would silently become the route's real timeout. The route's own
 * {@code response-timeout} (2 s, in {@code application.yml}) is the deadline and this one sits
 * above it, so the HTTP client gives up first and says why.
 */
@Configuration(proxyBeanMethods = false)
class GatewayResilienceConfig {

    /** The breaker's name, as referenced by the route's {@code CircuitBreaker} filter. */
    static final String CATALOG = "catalog";

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> catalogCircuitBreaker() {
        return factory -> factory.configure(
                builder -> builder
                        .circuitBreakerConfig(CircuitBreakerConfig.custom()
                                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(5)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(CatalogFallbackController.RETRY_AFTER)
                                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .build())
                        .timeLimiterConfig(TimeLimiterConfig.custom()
                                .timeoutDuration(Duration.ofSeconds(5))
                                .build()),
                CATALOG);
    }
}

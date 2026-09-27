package com.ecomdemo.resilience;

import com.ecomdemo.clients.catalog.CatalogClient;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the HTTP catalog client in {@link ResilientCatalog} as the context starts.
 *
 * <p><strong>Why a {@link BeanPostProcessor} and not a second {@code @Primary} bean.</strong> A
 * decorator declared as another {@code CatalogGateway} bean would compete with the one it
 * decorates — and in the integration tests, with the in-memory fake that is already
 * {@code @Primary}. Replacing the HTTP client IN PLACE avoids both: there is still exactly one
 * catalog client bean, it is simply the resilient one. The test fake is not a
 * {@link CatalogClient}, so it is left alone and the tests keep testing business logic.
 *
 * <p>This is also exactly what {@code @CircuitBreaker} and {@code @Retry} annotations do — Spring
 * AOP is a post-processor that swaps a bean for a proxy. Doing it by hand costs a dozen lines and
 * buys one thing the annotations hide: the ORDER of the layers is written in
 * {@code ResilientCatalog} rather than implied by aspect precedence.
 *
 * <p>The configuration comes from {@code resilience4j.*.instances.catalog.*} properties, read by
 * Resilience4j's Boot auto-configuration into the three registries. Taking the instances FROM the
 * registries (rather than building them here) is what binds them to Micrometer, and so to Grafana.
 */
@Configuration(proxyBeanMethods = false)
class CatalogResilienceConfig {

    /** The instance name used in properties, metrics and the dashboard. */
    static final String CATALOG = "catalog";

    /**
     * {@code static}, and the registries are {@link ObjectProvider}s: a post-processor is created
     * before ordinary beans, and anything it depended on directly would be created early too —
     * too early to be post-processed itself. The providers are only resolved when the catalog
     * client appears, by which time the registries are ordinary, fully configured beans.
     */
    @Bean
    static BeanPostProcessor catalogResilience(
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
            ObjectProvider<RetryRegistry> retries,
            ObjectProvider<BulkheadRegistry> bulkheads) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof CatalogClient http) {
                    return new ResilientCatalog(
                            http,
                            circuitBreakers.getObject().circuitBreaker(CATALOG),
                            retries.getObject().retry(CATALOG),
                            bulkheads.getObject().bulkhead(CATALOG));
                }
                return bean;
            }
        };
    }
}

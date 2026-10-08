package com.ecomdemo.resilience;

import com.ecomdemo.clients.inventory.InventoryClient;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the HTTP inventory client in {@link ResilientInventory} as the context starts (KI-004).
 *
 * <p>Exactly the mechanism of {@link CatalogResilienceConfig}, which explains why a
 * {@link BeanPostProcessor} and why the registries are {@link ObjectProvider}s. The in-memory
 * inventory the integration tests use is not an {@link InventoryClient}, so it is left alone.
 */
@Configuration(proxyBeanMethods = false)
class InventoryResilienceConfig {

    /** The instance name used in properties, metrics and the dashboard. */
    static final String INVENTORY = "inventory";

    @Bean
    static BeanPostProcessor inventoryResilience(
            ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
            ObjectProvider<RetryRegistry> retries,
            ObjectProvider<BulkheadRegistry> bulkheads) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof InventoryClient http) {
                    return new ResilientInventory(
                            http,
                            circuitBreakers.getObject().circuitBreaker(INVENTORY),
                            retries.getObject().retry(INVENTORY),
                            bulkheads.getObject().bulkhead(INVENTORY));
                }
                return bean;
            }
        };
    }
}

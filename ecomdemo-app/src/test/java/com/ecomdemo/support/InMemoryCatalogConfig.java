package com.ecomdemo.support;

import com.ecomdemo.clients.catalog.CatalogGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registers the fake catalogue, imported by {@link IntegrationTest} so every integration test in
 * this module shares ONE Spring context rather than one per test class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryCatalogConfig {

    @Bean
    @Primary
    CatalogGateway inMemoryCatalog(com.ecomdemo.clients.inventory.InventoryGateway inventory) {
        // It takes the inventory fake, because the real catalog-service calls the real
        // inventory-service on every write. A fake that skipped that would let a test create a
        // product with stock and then fail to buy it.
        return new InMemoryCatalog(inventory);
    }
}

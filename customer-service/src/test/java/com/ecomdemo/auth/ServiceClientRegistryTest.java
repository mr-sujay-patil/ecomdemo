package com.ecomdemo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.jwt.ServiceTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the service-to-service permission model: the scopes each client gets, as configured in
 * customer-service's real {@code application.properties} (Phase 33).
 *
 * <p>Changing a scope changes what a service may do to another, so it should be a deliberate edit
 * to this test as well as to the configuration, visible in review. It also documents a lesson the
 * first compose run taught: catalog-service does not only READ stock, it sets a product's stock on
 * create, update and delete, and with {@code inventory:read} alone every product write was a 500.
 * The in-memory inventory in catalog-service's own tests could not show that.
 */
@DisplayName("The service clients and their scopes")
class ServiceClientRegistryTest {

    @Configuration
    @EnableConfigurationProperties(ServiceClientProperties.class)
    static class Registry {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Registry.class)
            .withInitializer(context -> {
                try {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.boot.env.PropertiesPropertySourceLoader()
                                    .load("main", new org.springframework.core.io.ClassPathResource(
                                            "application.properties")).getFirst());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

    @Test
    @DisplayName("each service gets exactly the scopes its calls need, and no more")
    void theScopesAreLeastPrivilege() {
        runner.run(context -> {
            var clients = context.getBean(ServiceClientProperties.class).serviceClients();

            assertThat(clients).containsOnlyKeys("gateway-service", "ecomdemo-app", "catalog-service");
            assertThat(clients.get("gateway-service").scopes())
                    .as("anonymous browsing only").containsExactly(ServiceTokens.CATALOG_READ);
            assertThat(clients.get("catalog-service").scopes())
                    .as("stock lookups, and the stock of a product it creates, updates or deletes")
                    .containsExactly(ServiceTokens.INVENTORY_READ, ServiceTokens.INVENTORY_WRITE);
            assertThat(clients.get("ecomdemo-app").scopes())
                    .as("checkout, the saga and the CSV import")
                    .containsExactly(ServiceTokens.CATALOG_READ, ServiceTokens.CATALOG_WRITE,
                            ServiceTokens.INVENTORY_READ, ServiceTokens.INVENTORY_WRITE, ServiceTokens.PAYMENT_SETTLE);
        });
    }
}

package com.ecomdemo.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where inventory-service is.
 *
 * <p>A property rather than a constant because the answer differs by environment — {@code
 * localhost:8082} for a developer running both from an IDE, {@code http://inventory-service:8082}
 * on the Compose network, and something a platform tells you in a real deployment. Service
 * discovery is the grown-up version of this and is deliberately not in this phase: one hard-coded
 * address per service, configurable, is the honest starting point, and the day there is more than
 * one instance of anything it will be visibly inadequate.
 *
 * @param baseUrl the root URL of inventory-service, with no trailing slash
 */
@ConfigurationProperties(prefix = "ecomdemo.inventory")
public record InventoryProperties(String baseUrl) {

    public InventoryProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8082" : baseUrl;
    }
}

package com.ecomdemo.clients.inventory;

import java.time.Duration;
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
 * <p><strong>Every remote call needs a timeout, and the default is none</strong> (KI-004). Until it
 * was fixed, an inventory-service that accepted a connection and went quiet held the calling request
 * thread for ever - in the application at checkout and in the catalogue listing, and in
 * catalog-service on every product write. Enough of those and the caller's pool is gone: a slow
 * dependency turned into a dead caller. See {@code CatalogProperties} for the measured reasons the
 * read timeout, not the connect timeout, is the real cost of an outage.
 *
 * @param baseUrl the root URL of inventory-service, with no trailing slash
 * @param connectTimeout how long to wait for the TCP handshake
 * @param readTimeout how long to wait for the answer once the request is sent. One stock row, or
 *     one batched listing, from a database: milliseconds when healthy, so half a second is generous
 */
@ConfigurationProperties(prefix = "ecomdemo.inventory")
public record InventoryProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public InventoryProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8082" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofMillis(250) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofMillis(500) : readTimeout;
    }
}

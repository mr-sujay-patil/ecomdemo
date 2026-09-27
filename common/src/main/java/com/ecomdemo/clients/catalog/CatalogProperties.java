package com.ecomdemo.clients.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where catalog-service is, and how long a caller is prepared to wait for it. Defaulted so a
 * developer running one service locally needs no config.
 *
 * <p><strong>Every remote call needs a timeout, and the default is none.</strong> Before Phase 22
 * a catalog-service that accepted a connection and then never answered would hold the calling
 * request thread for ever, and enough of those would exhaust the caller's thread pool — a slow
 * dependency turning into a dead caller. That is how one failure cascades.
 *
 * <p>There are two read timeouts because there are two very different kinds of call:
 * <ul>
 *   <li>{@code readTimeout} — a single product read or write. Normally a few milliseconds (reads
 *       are served from catalog-service's Redis cache), so one second is already generous. A
 *       shopper is waiting on it.</li>
 *   <li>{@code bulkReadTimeout} — the CSV import's batch upsert. One chunk writes up to a hundred
 *       products, each of which makes catalog-service call inventory-service. A one-second limit
 *       there would fail imports that were merely busy, not broken.</li>
 * </ul>
 * A single "timeout for the service" would be either too long for the shopper or too short for
 * the import. Timeouts belong to operations, not to hosts.
 */
@ConfigurationProperties(prefix = "ecomdemo.catalog")
public record CatalogProperties(
        String baseUrl, Duration connectTimeout, Duration readTimeout, Duration bulkReadTimeout) {

    public CatalogProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8081" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofMillis(500) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(1) : readTimeout;
        bulkReadTimeout = bulkReadTimeout == null ? Duration.ofSeconds(30) : bulkReadTimeout;
    }
}

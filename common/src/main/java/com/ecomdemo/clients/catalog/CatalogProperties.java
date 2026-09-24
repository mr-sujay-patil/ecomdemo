package com.ecomdemo.clients.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where catalog-service is. Defaulted so a developer running one service locally needs no config. */
@ConfigurationProperties(prefix = "ecomdemo.catalog")
public record CatalogProperties(String baseUrl) {

    public CatalogProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8081" : baseUrl;
    }
}

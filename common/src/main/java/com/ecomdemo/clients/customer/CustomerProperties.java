package com.ecomdemo.clients.customer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where customer-service is. Defaulted so a developer running one service locally needs no config. */
@ConfigurationProperties(prefix = "ecomdemo.customer")
public record CustomerProperties(String baseUrl) {

    public CustomerProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8083" : baseUrl;
    }
}

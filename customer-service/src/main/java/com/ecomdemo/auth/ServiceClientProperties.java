package com.ecomdemo.auth;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The services allowed to ask for a token, each with its secret and its scopes, under
 * {@code ecomdemo.auth.service-clients} (Phase 33).
 *
 * <p>This list IS the service-to-service permission model: whatever scopes a client has here are the
 * only scopes its tokens will ever carry, and each callee checks the scope its endpoint needs. A
 * client whose secret is unset (its environment variable is empty) cannot authenticate at all.
 *
 * @param serviceClients client id (the calling service's {@code spring.application.name}) → client
 */
@ConfigurationProperties(prefix = "ecomdemo.auth")
public record ServiceClientProperties(Map<String, Client> serviceClients) {

    public ServiceClientProperties {
        serviceClients = serviceClients == null ? Map.of() : Map.copyOf(serviceClients);
    }

    /** One service: the secret it proves itself with, and what its tokens may do. */
    public record Client(String secret, List<String> scopes) {

        public Client {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}

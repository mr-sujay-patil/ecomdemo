package com.ecomdemo.inventory;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.jwt.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} that talks to inventory-service, and gives it an identity.
 *
 * <p>Phase 20b's first run of the smoke test failed here, usefully: inventory-service declares the
 * resource-server starter, so without a filter chain of its own Spring Boot fell back to securing
 * <em>everything</em> with a generated password, and the app's calls came back {@code 401}. The
 * catalogue listing turned that into a {@code 500}. Two services, two halves of one decision that
 * used to be a method call.
 */
@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
class InventoryClientConfig {

    /**
     * The name this application presents to inventory-service. It is what shows up in that
     * service's request log, so the field is worth a constant rather than a literal at the call
     * site — when order-service is the one making these calls, this is the single line that
     * changes.
     */
    private static final String CALLER = "ecomdemo-app";

    @Bean
    ServiceTokenProvider inventoryServiceTokenProvider(JwtEncoder encoder, JwtProperties properties) {
        return new ServiceTokenProvider(encoder, properties, CALLER);
    }

    /**
     * A builder of our own, deliberately not the auto-configured {@code RestClient.Builder}.
     *
     * <p>The interceptor below attaches a bearer token for <em>inventory-service</em>. Customising
     * the shared builder would attach it to every {@code RestClient} anyone later builds from it,
     * which is how a credential ends up on a request to a third party. The scope of a token should
     * match the scope of the client that carries it.
     */
    @Bean
    RestClient inventoryRestClient(
            RestClient.Builder builder,
            InventoryProperties properties,
            ServiceTokenProvider tokens) {
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return execution.execute(request, body);
                })
                .build();
    }
}

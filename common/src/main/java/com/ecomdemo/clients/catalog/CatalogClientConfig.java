package com.ecomdemo.clients.catalog;

import com.ecomdemo.jwt.ServiceTokenProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The {@link RestClient} that talks to catalog-service, carrying the caller's service identity.
 *
 * <p>A builder of its own — {@code builder.clone()} — deliberately, for the reason
 * {@code InventoryClientConfig} gives: customising the shared auto-configured builder would attach
 * this bearer token to every {@code RestClient} anyone later builds from it, which is how a
 * credential ends up on a request to a third party.
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogClientConfig {

    @Bean
    RestClient catalogRestClient(
            RestClient.Builder builder,
            CatalogProperties properties,
            ServiceTokenProvider serviceTokenProvider) {
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(serviceTokenProvider.token());
                    return execution.execute(request, body);
                })
                .build();
    }
}

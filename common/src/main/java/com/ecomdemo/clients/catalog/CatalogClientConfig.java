package com.ecomdemo.clients.catalog;

import com.ecomdemo.jwt.ServiceTokenProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The {@link RestClient}s that talk to catalog-service, carrying the caller's service identity.
 *
 * <p>A builder of its own — {@code builder.clone()} — deliberately, for the reason
 * {@code InventoryClientConfig} gives: customising the shared auto-configured builder would attach
 * this bearer token to every {@code RestClient} anyone later builds from it, which is how a
 * credential ends up on a request to a third party.
 *
 * <p><strong>Two clients, one per timeout.</strong> The request factory is where a timeout lives,
 * and it is fixed when a client is built, so a different read timeout for the bulk upsert means a
 * different client. See {@link CatalogProperties} for why the two numbers differ. The factory is
 * spring-web's own {@link JdkClientHttpRequestFactory} over the JDK's {@link HttpClient} —
 * {@code common} has no reason to carry Spring Boot's HTTP-client module just to set two numbers.
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogClientConfig {

    @Bean
    RestClient catalogRestClient(
            RestClient.Builder builder,
            CatalogProperties properties,
            ServiceTokenProvider serviceTokenProvider) {
        return build(builder, properties, properties.readTimeout(), serviceTokenProvider);
    }

    @Bean
    RestClient catalogBulkRestClient(
            RestClient.Builder builder,
            CatalogProperties properties,
            ServiceTokenProvider serviceTokenProvider) {
        return build(builder, properties, properties.bulkReadTimeout(), serviceTokenProvider);
    }

    private static RestClient build(
            RestClient.Builder builder,
            CatalogProperties properties,
            Duration readTimeout,
            ServiceTokenProvider serviceTokenProvider) {
        // Connect timeout: how long to wait for the TCP handshake. Read timeout: how long to wait
        // for the response once the request is sent. They catch different failures - a STOPPED
        // container is never connected to (and, with its old IP still in the JVM's DNS cache, is
        // not refused either: the SYN simply goes unanswered), a HUNG one accepts the connection
        // and then goes quiet. Each needs its own limit to be noticed at all.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(serviceTokenProvider.token());
                    return execution.execute(request, body);
                })
                .build();
    }
}

package com.ecomdemo.clients.customer;

import com.ecomdemo.jwt.ServiceTokenProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The {@link RestClient} for customer-service.
 *
 * <p>It carries the service token like the others, and that is not redundant with the caller's own
 * token being forwarded on some calls: the SERVICE identity is how customer-service knows the request
 * came from inside the system at all, and the forwarded bearer token — set per request, on the two
 * methods that need it — is how it knows which human. Login and register carry only the first, because
 * there is no human identity to carry yet.
 *
 * <p>{@code builder.clone()}, as everywhere else: customising the shared auto-configured builder would
 * attach this token to every {@code RestClient} anyone later builds from it.
 */
@Configuration
@EnableConfigurationProperties(CustomerProperties.class)
class CustomerClientConfig {

    @Bean
    RestClient customerRestClient(
            RestClient.Builder builder,
            CustomerProperties properties,
            ServiceTokenProvider serviceTokenProvider) {
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    // Only if the caller has not already set one: currentProfile() forwards the
                    // human's token, and overwriting it with the service's would answer the wrong
                    // person's profile - which is the kind of bug that returns somebody else's data.
                    if (!request.getHeaders().containsHeader(org.springframework.http.HttpHeaders.AUTHORIZATION)) {
                        request.getHeaders().setBearerAuth(serviceTokenProvider.token());
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}

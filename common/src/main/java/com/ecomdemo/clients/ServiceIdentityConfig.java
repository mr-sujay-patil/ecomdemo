package com.ecomdemo.clients;

import com.ecomdemo.jwt.ClientCredentialsTokenProvider;
import com.ecomdemo.jwt.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The identity a service presents when it calls another service.
 *
 * <p><strong>It lives in {@code com.ecomdemo.clients}, and the package is the point.</strong> Only a
 * service that calls another service needs a service token, and only such a service scans this
 * package. "Everyone verifies, only callers need a token" is the rule, and putting the bean where
 * callers look is how it is enforced rather than remembered.
 *
 * <p><strong>One bean, and the client id is the CALLER's name.</strong> A service's identity belongs
 * to the service, not to whoever it is ringing up; it comes from {@code spring.application.name},
 * which is already what its logs and metrics are tagged with.
 *
 * <p>Since Phase 33 the token is not minted here but fetched from customer-service with this
 * service's own client secret ({@link ClientCredentialsTokenProvider}); the scopes it carries are
 * whatever customer-service has on record for this client id.
 */
@Configuration
public class ServiceIdentityConfig {

    @Bean
    public ServiceTokenProvider serviceTokenProvider(
            @Value("${ecomdemo.service-identity.token-uri:http://localhost:8083/oauth2/token}") String tokenUri,
            @Value("${ecomdemo.service-identity.client-id:${spring.application.name}}") String clientId,
            @Value("${ecomdemo.service-identity.client-secret:}") String clientSecret) {
        return new ClientCredentialsTokenProvider(tokenUri, clientId, clientSecret);
    }
}

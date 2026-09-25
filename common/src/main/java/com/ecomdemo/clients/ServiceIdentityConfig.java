package com.ecomdemo.clients;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.jwt.ServiceTokenProvider;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The identity a service presents when it calls another service.
 *
 * <p><strong>It lives in {@code com.ecomdemo.clients}, and the package is the point.</strong> Only a
 * service that calls another service needs to sign anything, and only such a service scans this
 * package. The first version of this sat in {@code com.ecomdemo.jwt} — which every service scans,
 * because every service must VERIFY tokens — and inventory-service refused to start: it calls
 * nobody, has no {@code JwtEncoder}, and was being asked for one. "Everyone verifies, only callers
 * sign" is the rule, and putting the bean where callers look is how it is enforced rather than
 * remembered.
 *
 * <p>The encoder is built here from the shared {@code SecretKey} rather than injected as a bean, so
 * this does not compete with the application's own {@code JwtEncoder} — the one that issues USER
 * tokens at login, which stays where accounts are owned.
 *
 * <p><strong>One bean, and the subject is the CALLER's name.</strong> An earlier draft had each
 * client configuration make its own provider, which was wrong twice: two beans of one type made
 * injection ambiguous, and both hard-coded {@code ecomdemo-app} — so catalog-service calling
 * inventory-service would have claimed to be the application. A service's identity belongs to the
 * service, not to whoever it is ringing up. The name comes from {@code spring.application.name},
 * which is already what its logs and metrics are tagged with.
 */
@Configuration
public class ServiceIdentityConfig {

    @Bean
    public ServiceTokenProvider serviceTokenProvider(
            SecretKey jwtSigningKey,
            JwtProperties properties,
            @Value("${spring.application.name}") String applicationName) {
        return new ServiceTokenProvider(
                new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey)), properties, applicationName);
    }
}

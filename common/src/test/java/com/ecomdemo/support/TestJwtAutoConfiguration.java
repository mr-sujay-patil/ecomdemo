package com.ecomdemo.support;

import com.ecomdemo.jwt.ServiceTokenProvider;
import com.ecomdemo.jwt.ServiceTokens;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Wires {@link TestJwt} into every service's test context, without a line in any test (Phase 33).
 *
 * <p>It is an AUTO-configuration, registered in this test-jar's
 * {@code META-INF/spring/...AutoConfiguration.imports}, so it applies to every {@code @SpringBootTest}
 * of a module that depends on the test-jar, and only there: production code never sees it.
 *
 * <ul>
 *   <li>A decoder that trusts the test key, only when the application defined none. No test profile
 *       sets {@code ecomdemo.jwt.jwk-set-uri}, so the JWKS decoder is absent and this one fills in;
 *       customer-service, which verifies with its own keys, keeps its own.
 *   <li>A service token that needs no customer-service. {@code @Primary}, because the application's
 *       own provider (which would call {@code /oauth2/token} over HTTP) is still defined. It carries
 *       every scope: the scope CHECKS are tested in each callee with tokens made for that purpose.
 * </ul>
 */
@AutoConfiguration
public class TestJwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    JwtDecoder testJwtDecoder() {
        return TestJwt.decoder();
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveJwtDecoder.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    ReactiveJwtDecoder testReactiveJwtDecoder() {
        return TestJwt.reactiveDecoder();
    }

    @Bean
    @Primary
    ServiceTokenProvider testServiceTokenProvider() {
        String token = TestJwt.service("test-service", ServiceTokens.CATALOG_READ, ServiceTokens.CATALOG_WRITE,
                ServiceTokens.INVENTORY_READ, ServiceTokens.INVENTORY_WRITE, ServiceTokens.PAYMENT_SETTLE);
        return () -> token;
    }
}

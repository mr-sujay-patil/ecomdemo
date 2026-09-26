package com.ecomdemo.gateway;

import com.ecomdemo.jwt.JwtAuthorities;
import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import reactor.core.publisher.Mono;

/**
 * The reactive half of token validation — the same checks the services make, made one hop earlier.
 *
 * <p><strong>Why this is not simply {@code JwtKeyConfig}.</strong> That class builds a
 * {@link org.springframework.security.oauth2.jwt.JwtDecoder}, which is the servlet interface: it
 * returns a decoded token, blocking while it does so. A reactive filter chain needs a
 * {@link ReactiveJwtDecoder}, which returns a {@link Mono}. The two are not interchangeable, and the
 * distinction is not cosmetic — a blocking decode on a Netty event loop would stall every other
 * connection that thread is serving.
 *
 * <p>What is shared is the part that matters: the {@code SecretKey} bean comes from
 * {@code JwtKeyConfig} (imported by {@link GatewayApplication}), so the key-length rule and the
 * missing-secret warning have exactly one implementation. The validator is built from the same
 * {@code JwtValidators.createDefaultWithIssuer} call for the same reason: signature, {@code exp},
 * {@code nbf} and {@code iss}, with the same clock-skew allowance between containers.
 *
 * <p><strong>The edge validates, and the services validate again.</strong> That is deliberate
 * duplication. If a route here were ever misconfigured — pointing at the wrong service, or missing
 * an authorisation rule — a service that trusted the edge would be wide open. Every service keeps
 * its own resource server, so the worst a gateway mistake can do is let a request through to
 * something that will reject it itself.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayJwtConfig {

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(properties.issuer());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * Turns the {@code roles} claim into authorities, with the same {@code ROLE_} prefix the
     * services use.
     *
     * <p>The prefix is the whole reason this bean exists. Spring Security's {@code hasRole("ADMIN")}
     * looks for an authority literally named {@code ROLE_ADMIN}, while the token carries
     * {@code "roles": ["ADMIN"]} — a shorter claim, because the prefix is a framework convention
     * rather than part of anyone's identity. Without this converter the default reads {@code scope}
     * or {@code scp}, finds neither, and every authorisation rule fails closed. The services get
     * this from {@code JwtAuthorities}, whose {@code ROLE_PREFIX} is reused here so the prefix has
     * one definition; the reactive chain needs the {@code Reactive} variant, with
     * the blocking converter wrapped by an adapter that runs it on a bounded-elastic thread.
     */
    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> reactiveJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenClaims.ROLES);
        authorities.setAuthorityPrefix(JwtAuthorities.ROLE_PREFIX);

        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new ReactiveJwtGrantedAuthoritiesConverterAdapter(authorities));
        return converter;
    }
}

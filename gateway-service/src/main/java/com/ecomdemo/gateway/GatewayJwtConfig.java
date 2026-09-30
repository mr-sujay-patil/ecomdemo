package com.ecomdemo.gateway;

import com.ecomdemo.jwt.JwtAuthorities;
import com.ecomdemo.jwt.JwtProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
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
 * <p>What is shared is the part that matters. Since Phase 33 both check the signature against
 * customer-service's PUBLIC keys, fetched from {@code ecomdemo.jwt.jwk-set-uri} and refetched when
 * a token names a key id the cache does not know (so a key rotation needs no restart here either),
 * and both use {@code JwtValidators.createDefaultWithIssuer}: {@code exp}, {@code nbf} and
 * {@code iss}, with the same clock-skew allowance between containers.
 *
 * <p><strong>The edge validates, and the services validate again.</strong> That is deliberate
 * duplication. If a route here were ever misconfigured — pointing at the wrong service, or missing
 * an authorisation rule — a service that trusted the edge would be wide open. Every service keeps
 * its own resource server, so the worst a gateway mistake can do is let a request through to
 * something that will reject it itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class GatewayJwtConfig {

    /** Only when a JWKS URI is configured: the tests supply a decoder that trusts their own key. */
    @Bean
    @ConditionalOnProperty(name = "ecomdemo.jwt.jwk-set-uri")
    ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(properties.issuer());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * Turns the {@code roles} claim into {@code ROLE_*} and, since Phase 33, the {@code scope} claim
     * of a service token into {@code SCOPE_*}: the same {@code JwtAuthorities.authorities()} every
     * service uses.
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
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new ReactiveJwtGrantedAuthoritiesConverterAdapter(JwtAuthorities.authorities()));
        return converter;
    }
}

package com.ecomdemo.security;

import com.ecomdemo.jwt.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The only service that can sign a token (Phase 33).
 *
 * <p>Phase 20b split Phase 9's JWT configuration along the line that matters: every service
 * VERIFIES, only this one ISSUES. With HS256 that line was aspirational, because verifying and
 * signing used the same shared secret and every service held it. Now it is enforced by
 * mathematics: this service holds RSA private keys ({@link SigningKeys}); everyone else only ever
 * receives the public halves, from {@code /oauth2/jwks}, and a public key cannot sign.
 *
 * <p>This service verifies its own tokens (a customer reading their profile) against the same key
 * set it signs with, in process, rather than fetching its own JWKS over HTTP.
 */
@Configuration
@EnableConfigurationProperties(SigningKeyProperties.class)
public class JwtConfig {

    @Bean
    public SigningKeys signingKeys(SigningKeyProperties properties) {
        return SigningKeys.from(properties);
    }

    /**
     * Signs with whichever key a token's header names ({@code kid}); {@code TokenService} always
     * names the active one. Given several keys, Nimbus refuses to guess, which is the behaviour
     * wanted.
     */
    @Bean
    public JwtEncoder jwtEncoder(SigningKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(keys.all()));
    }

    /** Verifies against every published key, so tokens signed before a rotation stay valid. */
    @Bean
    public JwtDecoder jwtDecoder(SigningKeys keys, JwtProperties properties) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, new ImmutableJWKSet<>(keys.all().toPublicJWKSet())));
        // Claims (exp, nbf, iss) are left to Spring's validators below, as Spring's own decoder
        // builders do, so expiry is judged the same way here as in every other service, with the
        // same clock-skew allowance.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}

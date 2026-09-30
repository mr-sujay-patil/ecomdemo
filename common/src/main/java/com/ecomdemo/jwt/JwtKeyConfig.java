package com.ecomdemo.jwt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * How every service except customer-service checks a token: against customer-service's PUBLIC keys.
 *
 * <p><strong>Phase 33 finished what Phase 9 wrote down.</strong> Phase 9 chose HS256 and warned:
 * <em>"The moment a second service needs to accept these tokens, HMAC becomes the wrong choice:
 * sharing the key with a verifier hands it the power to issue."</em> From Phase 20b every service
 * held {@code JWT_SECRET}, so any of them could mint a token claiming to be any administrator. Now
 * customer-service signs with an RSA private key that nothing else holds (RS256), and this decoder
 * only ever sees the public half.
 *
 * <p><strong>JWKS, and why rotation needs no restart.</strong> The decoder fetches customer-service's
 * key set from {@code ecomdemo.jwt.jwk-set-uri} (a JSON Web Key Set) the first time it needs it and
 * caches it. Every token names its key in the {@code kid} header; a {@code kid} the cache does not
 * know makes the decoder fetch the set again. So when customer-service publishes a new key, then
 * starts signing with it, no verifier has to be told or restarted.
 *
 * <p>It is only created when a JWKS URI is configured. customer-service has none (it verifies with
 * the keys it holds), and tests supply their own decoder built from a test key.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

    @Bean
    @ConditionalOnProperty(name = "ecomdemo.jwt.jwk-set-uri")
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}

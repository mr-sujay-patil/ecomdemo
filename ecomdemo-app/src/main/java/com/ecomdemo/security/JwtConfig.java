package com.ecomdemo.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The half of Phase 9's JWT configuration that did <strong>not</strong> move to {@code common}.
 *
 * <p>Phase 20b split this class in two along the line that matters: every service must
 * <em>verify</em> a token, so the key and the {@link org.springframework.security.oauth2.jwt.JwtDecoder}
 * are in {@link com.ecomdemo.jwt.JwtKeyConfig}; only the service that owns logins may
 * <em>issue</em> one, so the encoder stayed here. When customer-service is extracted, this file
 * goes with it and no other service ever gains the ability to mint a token.
 *
 * <p>That line is aspirational rather than enforced, and the reason is in {@code JwtKeyConfig}:
 * HS256 verifies with the same secret it signs with, so every service <em>could</em> mint a token
 * whether or not it has an encoder bean. Keeping the bean where it belongs is still worth doing —
 * it makes the intended shape obvious, and it is what survives the move to asymmetric keys.
 */
@Configuration
@Import(com.ecomdemo.jwt.JwtKeyConfig.class)
public class JwtConfig {

    /** Signs. Used only by the login endpoint. */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }
}

package com.ecomdemo.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

/**
 * The signing key, and the two beans that use it.
 *
 * <p><strong>HMAC, not RSA.</strong> HS256 signs and verifies with the <em>same</em> secret, so
 * whoever can check a token can also mint one. That is fine here because one application does
 * both jobs. The moment a second service needs to accept these tokens, HMAC becomes the wrong
 * choice: sharing the key with a verifier hands it the power to issue. RS256 splits that in two
 * — a private key that signs, a public key that anyone may hold and verify with — which is why
 * every real identity provider publishes a JWKS endpoint of public keys and no secrets at all.
 * The symmetric choice here is a deliberate simplification of a single-application phase, not a
 * recommendation.
 *
 * <p><strong>The key is never in Git.</strong> It is read from {@code JWT_SECRET}. When that is
 * unset the application generates a random one at startup and says so, loudly: everything works,
 * but every token is invalidated by a restart because the new key cannot verify the old
 * signatures. That is the right trade for a learning project — no manual setup step, and no
 * committed secret — and it fails visibly rather than silently in any environment where tokens
 * are expected to survive a deploy.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /**
     * HS256 is HMAC-SHA256, so the key must be at least as long as the hash it feeds: 256 bits,
     * 32 bytes. Nimbus refuses a shorter one rather than silently weakening the signature, and
     * this check turns that into a message that says what to do about it.
     */
    private static final int MINIMUM_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            log.warn("""
                    JWT_SECRET is not set, so a random signing key was generated for this run. \
                    Logins work, but EVERY TOKEN BECOMES INVALID WHEN THIS APPLICATION RESTARTS, \
                    and a second instance would reject tokens issued by this one. Set JWT_SECRET \
                    to at least {} characters before running anything you expect to keep working.""",
                    MINIMUM_KEY_BYTES);
            byte[] generated = new byte[MINIMUM_KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            return new SecretKeySpec(generated, ALGORITHM);
        }

        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is only %d bytes. HS256 needs at least %d (256 bits) — a shorter "
                            .formatted(bytes.length, MINIMUM_KEY_BYTES)
                            + "key weakens every signature, so it is refused rather than padded.");
        }
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    /** Signs. Used only by the login endpoint. */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /**
     * Verifies, on every request.
     *
     * <p>What it checks is worth being explicit about, because it is the whole security model:
     * the signature (so the payload cannot have been edited), {@code exp} and {@code nbf} (so an
     * old token stops working), and the issuer (so a token minted elsewhere with a leaked key is
     * still rejected here). Notice what it does <em>not</em> do: touch the database. That is the
     * point of a stateless token — and also its drawback, since nothing can revoke one before it
     * expires.
     *
     * <p>{@code JwtValidators.createDefaultWithIssuer} contributes the timestamp and issuer
     * checks together, including a small clock skew allowance so that two machines a second
     * apart do not reject each other's tokens.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(properties.issuer());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** The claim names this application writes and reads. */
    public static final class Claims {

        /** The account's database id. Saves a lookup by username on every request. */
        public static final String USER_ID = "uid";

        /** The roles, without the {@code ROLE_} prefix Spring Security adds back on the way in. */
        public static final String ROLES = "roles";

        /** The username. Standard: {@link JwtClaimNames#SUB}. */
        public static final String SUBJECT = JwtClaimNames.SUB;

        private Claims() {
        }
    }
}

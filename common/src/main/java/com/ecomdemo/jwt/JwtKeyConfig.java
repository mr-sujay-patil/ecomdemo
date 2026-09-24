package com.ecomdemo.jwt;

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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The signing key and the decoder built from it — the half of Phase 9's {@code JwtConfig} that
 * every service needs.
 *
 * <p><strong>The split made this file's oldest comment come true.</strong> Phase 9 wrote, about
 * choosing HS256: <em>"The moment a second service needs to accept these tokens, HMAC becomes the
 * wrong choice: sharing the key with a verifier hands it the power to issue."</em> Phase 20b is
 * that moment. {@code JWT_SECRET} is now handed to every service in {@code compose.yaml}, so
 * inventory-service can mint a token claiming to be any administrator it likes, and nothing would
 * detect it.
 *
 * <p>It is kept anyway, knowingly, because fixing it properly is a phase of its own: RS256 or
 * EdDSA, a private key held only by the issuer, a JWKS endpoint the others fetch public keys from,
 * and key rotation on top. Swapping the algorithm is the small part. Recorded in
 * {@code docs/decisions.md} so that it is a decision rather than an oversight.
 *
 * <p><strong>The key is never in Git.</strong> It is read from {@code JWT_SECRET}. When that is
 * unset a random one is generated at startup and the service says so, loudly: everything works,
 * but every token is invalidated by a restart because the new key cannot verify the old
 * signatures. Across several services that now means something worse than it used to — each one
 * generates a <em>different</em> random key, so a token issued by one is rejected by the next, and
 * the failure looks like a bug rather than like missing configuration. The warning below says so.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    /**
     * HS256 is HMAC-SHA256, so the key must be at least as long as the hash it feeds: 256 bits,
     * 32 bytes. Nimbus refuses a shorter one rather than silently weakening the signature, and
     * this check turns that into a message that says what to do about it.
     */
    private static final int MINIMUM_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * One instance, reused.
     *
     * <p>Constructing a {@code SecureRandom} per call is the mistake this guards against: each
     * new instance re-seeds from the operating system's entropy source, which is slow, and on
     * some platforms a burst of fresh instances created in the same moment can be seeded from
     * correlated state. A single instance is also explicitly thread-safe, and it accumulates
     * entropy rather than starting from scratch. This is only reached once at startup, but "it
     * only happens once" is how the habit survives into code where it happens constantly.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            log.warn("""
                    JWT_SECRET is not set, so a random signing key was generated for this run. \
                    Logins work, but EVERY TOKEN BECOMES INVALID WHEN THIS SERVICE RESTARTS, and \
                    every OTHER service generated a different random key — so a token issued by \
                    one of them is rejected by this one and the failure looks like a bug. Set \
                    JWT_SECRET, to at least {} characters, for every service at once.""",
                    MINIMUM_KEY_BYTES);
            byte[] generated = new byte[MINIMUM_KEY_BYTES];
            SECURE_RANDOM.nextBytes(generated);
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
     * apart do not reject each other's tokens. That allowance stopped being theoretical with the
     * split: these are genuinely different containers now, each with its own view of the clock.
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
}

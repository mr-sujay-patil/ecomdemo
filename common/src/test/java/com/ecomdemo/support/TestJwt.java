package com.ecomdemo.support;

import com.ecomdemo.shared.TokenClaims;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Tokens for tests, signed the way customer-service signs real ones (RS256, a {@code kid}), with a
 * key pair generated once per test JVM (Phase 33).
 *
 * <p>Services verify with the PUBLIC half ({@link #decoder()}, wired in by
 * {@link TestJwtAutoConfiguration}); tests mint with the private half. No key is ever committed:
 * the pair exists only in memory for one test run, which is the same promise the real system makes
 * about customer-service's key.
 */
public final class TestJwt {

    public static final String ISSUER = "ecomdemo";

    public static final String KEY_ID = "test-key";

    private static final RSAKey KEY = generate(KEY_ID);

    private TestJwt() {
    }

    /** A fresh RSA key pair, for tests that need a key the system does NOT trust. */
    public static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key pair every service's test context trusts. */
    public static RSAKey key() {
        return KEY;
    }

    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ReactiveJwtDecoder reactiveDecoder() {
        try {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A person's token, as customer-service issues one at login. */
    public static String user(String username, long userId, String... roles) {
        return sign(KEY, JwtClaimsSet.builder()
                .subject(username)
                .claim(TokenClaims.USER_ID, userId)
                .claim(TokenClaims.ROLES, List.of(roles)));
    }

    /** A service's token, as customer-service issues one for the client credentials grant. */
    public static String service(String clientId, String... scopes) {
        return sign(KEY, JwtClaimsSet.builder()
                .subject(clientId)
                .claim(TokenClaims.SCOPE, String.join(" ", scopes)));
    }

    /** A person's token signed with some OTHER key: well-formed, and not to be trusted. */
    public static String userSignedBy(RSAKey key, String username, String... roles) {
        return sign(key, JwtClaimsSet.builder()
                .subject(username)
                .claim(TokenClaims.USER_ID, 1L)
                .claim(TokenClaims.ROLES, List.of(roles)));
    }

    private static String sign(RSAKey key, JwtClaimsSet.Builder claims) {
        Instant now = Instant.now();
        JwtClaimsSet complete = claims.issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, complete)).getTokenValue();
    }
}

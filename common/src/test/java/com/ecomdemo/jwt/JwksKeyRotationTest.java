package com.ecomdemo.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.support.TestJwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * A verifier follows customer-service through a key rotation with no restart (Phase 33).
 *
 * <p>The decoder under test is the one every service builds ({@link JwtKeyConfig}), pointed at a
 * JDK {@link HttpServer} that plays customer-service's {@code /oauth2/jwks}. The rotation is the
 * documented one: publish the new key next to the old, sign with the new, drop the old once no
 * token signed with it can still be alive.
 */
@DisplayName("Key rotation through JWKS")
class JwksKeyRotationTest {

    private static final RSAKey OLD = TestJwt.generate("key-2026-09");
    private static final RSAKey NEW = TestJwt.generate("key-2026-10");

    private HttpServer server;
    private final AtomicReference<JWKSet> published = new AtomicReference<>(new JWKSet(OLD));
    private final AtomicInteger fetches = new AtomicInteger();
    private JwtDecoder decoder;

    @BeforeEach
    void startTheJwksEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth2/jwks", exchange -> {
            fetches.incrementAndGet();
            // The PUBLIC halves only, exactly as customer-service publishes them.
            byte[] body = published.get().toPublicJWKSet().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String uri = "http://localhost:" + server.getAddress().getPort() + "/oauth2/jwks";
        decoder = new JwtKeyConfig().jwtDecoder(new JwtProperties(TestJwt.ISSUER, Duration.ofMinutes(15), uri));
    }

    @AfterEach
    void stopIt() {
        server.stop(0);
    }

    private static String signedWith(RSAKey key) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(TestJwt.ISSUER).subject("asha")
                .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim("roles", List.of("CUSTOMER")).build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    @DisplayName("tokens signed with the old key and with the new one are both accepted during the overlap")
    void rotationNeedsNoRestart() {
        String beforeRotation = signedWith(OLD);
        assertThat(decoder.decode(beforeRotation).getSubject()).isEqualTo("asha");

        // Step 1 and 2: customer-service publishes the new key next to the old one and signs with it.
        published.set(new JWKSet(List.of(NEW, OLD)));
        String afterRotation = signedWith(NEW);

        // The verifier has never seen kid "key-2026-10": it fetches the set again and accepts it,
        // while the token issued before the rotation still verifies. No restart, no configuration.
        assertThat(decoder.decode(afterRotation).getSubject()).isEqualTo("asha");
        assertThat(decoder.decode(beforeRotation).getSubject()).isEqualTo("asha");
        assertThat(fetches.get()).as("the set is fetched again for the unknown kid").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a token signed with a key customer-service never published is rejected")
    void aForeignKeyIsRejected() {
        String forged = TestJwt.userSignedBy(TestJwt.generate("key-2026-09"), "admin", "ADMIN");

        // Same kid as the real key, different key material: the signature check is what refuses it.
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }
}

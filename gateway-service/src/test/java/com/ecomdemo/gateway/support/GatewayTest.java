package com.ecomdemo.gateway.support;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import com.ecomdemo.support.RedisContainerConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The shared base for tests that go through the gateway's real filter chain.
 *
 * <p><strong>A real Redis, because the rate limiter is a default filter.</strong> It applies to every
 * route, so there is no request through this application that does not consult it — a test without a
 * store would fail on the limiter rather than on whatever it meant to assert. {@link
 * RedisContainerConfig} comes from {@code common}'s test-jar, which keeps this container identical to
 * the one catalog-service's tests use and keeps the shared context cache key intact.
 *
 * <p><strong>No upstream services run.</strong> That is deliberate and it is what makes these tests
 * fast and honest: everything asserted here happens BEFORE the proxy call. A 401, a 403 and a 429 are
 * all produced by the gateway itself. A request that survives all three reaches a route whose target
 * is not listening and comes back 503 — which is a perfectly good "the edge let this through", and a
 * far more precise claim than standing up four services to observe the same thing.
 *
 * <p>Tokens are MINTED rather than obtained by logging in, following {@code CatalogIntegrationTest}
 * from Phase 20c. The gateway has no login endpoint and never will; customer-service issues tokens.
 * Minting also lets a test ask for a role nobody would grant.
 *
 * <p>{@code protected} rather than package-private, and that is not incidental: Phase 20d lost a
 * whole test class to a package-private {@code @BeforeEach} that Java never inherited because the
 * subclasses lived in another package. Every member here is reachable from a subclass anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(RedisContainerConfig.class)
public abstract class GatewayTest {

    private static final Duration FALLBACK_EXPIRY = Duration.ofMinutes(15);

    @Autowired
    protected WebTestClient web;

    @Autowired
    private SecretKey jwtSigningKey;

    @Autowired
    private JwtProperties jwtProperties;

    /**
     * A token carrying exactly the roles asked for.
     *
     * <p>Signed with the same secret the gateway verifies with, and carrying the same {@code iss} it
     * requires — so a test that wants to be REJECTED has to break one of those on purpose rather than
     * rely on an accident.
     */
    protected String tokenWithRoles(String username, String... roles) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
        Instant now = Instant.now();
        Duration expiry = jwtProperties.expiry() == null ? FALLBACK_EXPIRY : jwtProperties.expiry();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(expiry))
                .claim(TokenClaims.USER_ID, 4242)
                .claim(TokenClaims.ROLES, List.of(roles))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

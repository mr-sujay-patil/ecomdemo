package com.ecomdemo.support;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for the {@code *IT} tests: the whole application on a real port, talking to a real
 * PostgreSQL in a container.
 *
 * <p>Every annotation here is part of the context cache key, so keeping them in one place is what
 * lets the integration tests share a single context — and therefore one PostgreSQL and one Redis
 * for the whole run rather than a pair per class. Add a {@code @MockitoBean} or a stray
 * {@code @TestPropertySource} to one subclass and that subclass silently gets a context, and both
 * containers, of its own.
 *
 * <p>{@link WebEnvironment#RANDOM_PORT} starts Tomcat on a free port and the injected
 * {@link TestRestTemplate} is pre-pointed at it. Requests therefore travel over real HTTP through
 * the real filter chain, Jackson and the {@code GlobalExceptionHandler} — unlike
 * {@code @WebMvcTest}, which calls the controller through a mock servlet environment with the
 * service layer stubbed out.
 *
 * <p>{@code TestRestTemplate} never throws on a 4xx or 5xx; it hands back the
 * {@link org.springframework.http.ResponseEntity} so the status itself can be asserted. That is
 * the difference from {@code RestTemplate} and the reason the error-path tests read as plainly
 * as the happy-path ones.
 *
 * <p>{@code @AutoConfigureTestRestTemplate} is new in Spring Boot 4. Boot 3 contributed the
 * {@code TestRestTemplate} bean to every {@code @SpringBootTest} with a real port; Boot 4 moved
 * the class into {@code spring-boot-resttestclient} and registers its auto-configuration only
 * when this annotation asks for it, so without it the field is simply not injected and the
 * context fails to start.
 *
 * <p>These tests are deliberately NOT {@code @Transactional}. A rolled-back test transaction
 * would never commit, so it could not prove that a real commit works, and the HTTP request runs
 * on a Tomcat thread that would not see the test thread's transaction anyway. They clean up after
 * themselves instead, through the same API they exercise.
 *
 * <p>Since Phase 8 most of the API needs credentials, and these tests send real ones. Since
 * Phase 9 that means a real round trip: {@link #asCustomer} and {@link #asAdmin} call
 * {@code POST /api/auth/login} over HTTP, get a genuinely signed JWT back, and attach it as
 * {@code Authorization: Bearer …} — so the password is verified against a real BCrypt hash in
 * the container's database, the token is signed by the running application's key, and every
 * later request has its signature, expiry and issuer checked by the real decoder. That is the
 * whole point of running at this level: {@code @WithMockUser} would skip the authentication it
 * is supposed to be exercising, and a hand-built {@code Jwt} would skip the signature.
 *
 * <p>{@link #rest} stays unauthenticated so that the "anonymous callers are refused" cases can
 * still be written.
 *
 * <p><strong>{@code InMemoryInventoryConfig} joined the imports in Phase 20b.</strong> Inventory is
 * a separate service now, so creating a product and checking out both cross a network — and these
 * tests are about carts, orders, caching and metrics, not about that network. The fake BEHAVES
 * like inventory so their assertions keep meaning what they meant; what it cannot prove, and does
 * not pretend to, is that the HTTP client speaks the protocol the real service serves. That is the
 * smoke test's job once Compose runs both.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({PostgresContainerConfig.class, RedisContainerConfig.class, KafkaContainerConfig.class,
        InMemoryInventoryConfig.class, InMemoryCatalogConfig.class})
@ActiveProfiles("it")
public abstract class IntegrationTest {

    /**
     * The seeded administrator from migration V5. This password is a documented throwaway
     * development credential, not a secret — see the migration for why it exists at all.
     */
    protected static final String ADMIN_USERNAME = "admin";

    /**
     * The seeded administrator's id, as customer-service's V1 writes it.
     *
     * <p>It is hard-coded because nothing enforces it any more: Phase 20d removed the foreign keys
     * from {@code cart} and {@code orders} to {@code users}, so the two databases agree by convention
     * and a migration. customer-service's {@code CatalogSchemaTest} equivalent checks its end.
     */
    protected static final long ADMIN_ID = 1L;

    protected static final String ADMIN_PASSWORD = "admin123";

    /** The password every account these tests create shares. Also throwaway, also not a secret. */
    protected static final String IT_PASSWORD = "integration-test-password";

    /** Unauthenticated. Use it for public endpoints and for the 401 cases. */
    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    private SecretKey jwtSigningKey;

    @Autowired
    private JwtProperties jwtProperties;

    /** A client carrying a freshly issued token for the seeded ADMIN. */
    /**
     * An ADMIN caller.
     *
     * <p><strong>It MINTS a token instead of logging in</strong>, and that is Phase 20d showing
     * through the test base. It used to POST to {@code /api/auth/login} and hold the token that came
     * back — an endpoint this application no longer serves, because checking a password is
     * customer-service's exclusive job now.
     *
     * <p>The same pattern {@code CatalogIntegrationTest} has used since 20c, and for the same reason:
     * a service with no login endpoint should not have tests that log in. What is signed here is a
     * real token, with the real secret, verified by the real filter chain — the only thing skipped is
     * the password check, which is not this application's to make.
     */
    protected TestRestTemplate asAdmin() {
        return withToken(tokenFor(ADMIN_ID, ADMIN_USERNAME, "ADMIN"));
    }

    /**
     * A CUSTOMER caller.
     *
     * <p>The id is derived from the username so that two different names get two different ids
     * without a registry to keep — which matters because carts and orders are keyed by id, and two
     * tests that accidentally shared one would see each other's data. {@code hashCode} is stable
     * within a JVM run, which is all one suite needs, and the offset keeps it clear of the seeded
     * administrator.
     */
    protected TestRestTemplate asCustomer(String username) {
        return withToken(tokenFor(idFor(username), username, "CUSTOMER"));
    }

    protected long idFor(String username) {
        return 1_000_000L + Math.abs(username.hashCode() % 1_000_000);
    }

    /** Signs a token the running application will accept, using the profile's configured secret. */
    protected String tokenFor(long userId, String username, String role) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(username)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .claim(TokenClaims.USER_ID, userId)
                .claim(TokenClaims.ROLES, List.of(role))
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    protected TestRestTemplate withToken(String token) {
        TestRestTemplate authenticated = new TestRestTemplate();
        authenticated.getRestTemplate()
                .setUriTemplateHandler(new DefaultUriBuilderFactory(rest.getRootUri()));
        authenticated.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        });
        return authenticated;
    }
}

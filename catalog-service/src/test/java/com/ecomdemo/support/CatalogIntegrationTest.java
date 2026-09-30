package com.ecomdemo.support;

import com.ecomdemo.jwt.ServiceTokens;
import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * The base for catalog-service's integration tests: the whole service on a random port, against a
 * real PostgreSQL and a real Redis.
 *
 * <p><strong>Why this is not the application's {@code IntegrationTest}.</strong> That one
 * authenticates by POSTing to {@code /api/auth/login} and holding the token it gets back. This
 * service has no login endpoint and never will — issuing tokens belongs to whoever owns accounts.
 * Its only caller is another service, so the only credential that makes sense here is a SERVICE
 * token, minted from the same shared secret the running service verifies with.
 *
 * <p>That difference is not an inconvenience to work around; it is the boundary being real. A test
 * base that could still log in would be proving something about a service that does not exist.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({PgVectorContainerConfig.class, RedisContainerConfig.class, InMemoryInventoryConfig.class})
@ActiveProfiles("it")
public abstract class CatalogIntegrationTest {

    /**
     * Unauthenticated. Useful on purpose: it is how a test proves that a path REQUIRES a token,
     * which is the assertion Phase 20b learned to make first.
     */
    @Autowired
    protected TestRestTemplate anonymous;

    /**
     * The one most tests want: a caller carrying the application's service token.
     *
     * <p>It is called {@code rest} because that is what it was called when these tests lived in the
     * application — but the meaning has changed underneath the name, and that is worth noticing.
     * There, {@code rest} was the ANONYMOUS client, because the catalogue's reads were public.
     * Here, nothing is public: this service's only caller is another service. The public listing
     * still exists, on the application, one hop away.
     */
    protected TestRestTemplate rest;

    /**
     * PROTECTED, not package-private, and that keyword is load-bearing.
     *
     * <p>A package-private method in a superclass is not inherited by a subclass in a DIFFERENT
     * package — and every test extending this class is in {@code com.ecomdemo.catalog} or
     * {@code com.ecomdemo.cache}, not {@code com.ecomdemo.support}. So JUnit never saw this
     * {@code @BeforeEach}, {@code rest} stayed null... and the tests still "passed", because Failsafe
     * was not bound in this module and none of them had ever run.
     *
     * <p>Two invisible failures stacked on each other, which is why the first real execution of these
     * integration tests found both at once.
     */
    @org.junit.jupiter.api.BeforeEach
    protected void authenticateAsTheApplication() {
        rest = asService();
    }


    @Autowired
    private JwtProperties jwtProperties;

    /** A caller identifying itself the way the application does. */
    protected TestRestTemplate asService() {
        String token = TestJwt.service("ecomdemo-app", ServiceTokens.CATALOG_READ, ServiceTokens.CATALOG_WRITE);
        return withToken(token);
    }

    /**
     * A caller carrying a USER's token, shaped like the ones customer-service issues - which is
     * what arrives here when the gateway relays a shopper's or an administrator's request.
     */
    protected TestRestTemplate asUser(String username, long userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim(TokenClaims.USER_ID, userId)
                .claim(TokenClaims.ROLES, List.of(roles))
                .build();
        String token = TestJwt.sign(claims);
        return withToken(token);
    }

    protected TestRestTemplate withToken(String token) {
        // Built from a bare TestRestTemplate rather than through RestTemplateBuilder, which is not
        // on this module's classpath: catalog-service uses RestClient to call inventory-service and
        // has no reason to carry the RestTemplate starter into production just to shape a test.
        TestRestTemplate authenticated = new TestRestTemplate();
        authenticated.getRestTemplate()
                .setUriTemplateHandler(new DefaultUriBuilderFactory(anonymous.getRootUri()));
        authenticated.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        });
        return authenticated;
    }
}

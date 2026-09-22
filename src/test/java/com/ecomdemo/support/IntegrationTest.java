package com.ecomdemo.support;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
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
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({PostgresContainerConfig.class, RedisContainerConfig.class, KafkaContainerConfig.class})
@ActiveProfiles("it")
public abstract class IntegrationTest {

    /**
     * The seeded administrator from migration V5. This password is a documented throwaway
     * development credential, not a secret — see the migration for why it exists at all.
     */
    protected static final String ADMIN_USERNAME = "admin";

    protected static final String ADMIN_PASSWORD = "admin123";

    /** The password every account these tests create shares. Also throwaway, also not a secret. */
    protected static final String IT_PASSWORD = "integration-test-password";

    /** Unauthenticated. Use it for public endpoints and for the 401 cases. */
    @Autowired
    protected TestRestTemplate rest;

    /** A client carrying a freshly issued token for the seeded ADMIN. */
    protected TestRestTemplate asAdmin() {
        return withToken(login(ADMIN_USERNAME, ADMIN_PASSWORD));
    }

    /**
     * Registers a CUSTOMER (if this container has not seen it yet), logs in, and returns a
     * client carrying the token.
     *
     * <p>Registration goes through the public endpoint rather than straight into the database,
     * so the password really is hashed by the application and really is verified back at login.
     * A repeat run inside the same container gets a 409, which is fine: the account is there
     * either way and the password has not changed.
     *
     * <p>Each test class uses a username of its own, because an account owns a cart — two
     * classes sharing a name would share a cart and interfere with each other.
     */
    protected TestRestTemplate asCustomer(String username) {
        rest.postForEntity(
                "/api/customers/register",
                new RegisterRequest(username, IT_PASSWORD, username + " (integration test)"),
                CustomerResponse.class);
        return withToken(login(username, IT_PASSWORD));
    }

    /** Exchanges credentials for a token over real HTTP, and fails the test if that does not work. */
    protected String login(String username, String password) {
        TokenResponse token = rest.postForObject(
                "/api/auth/login", new LoginRequest(username, password), TokenResponse.class);
        Assertions.assertThat(token)
                .as("login as %s should return a token", username)
                .isNotNull();
        Assertions.assertThat(token.accessToken()).isNotBlank();
        return token.accessToken();
    }

    /**
     * A client that sends the given token on every request.
     *
     * <p>An interceptor rather than a header passed to every call: it keeps these tests reading
     * exactly as they did under Basic, and it mirrors what a real client does — attach the token
     * once and forget about it until it expires.
     *
     * <p>The root URI is copied from the injected template, because that is what carries the
     * random port Tomcat was given; without it the relative paths in the tests would go nowhere.
     * It is applied through a {@link DefaultUriBuilderFactory} rather than the builder's
     * {@code rootUri(String)}, which Spring Boot 4 has deprecated for removal — the factory is
     * what that method was configuring all along.
     */
    protected TestRestTemplate withToken(String token) {
        return new TestRestTemplate(new RestTemplateBuilder()
                .uriTemplateHandler(new DefaultUriBuilderFactory(rest.getRootUri()))
                .additionalInterceptors((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(token);
                    return execution.execute(request, body);
                }));
    }
}

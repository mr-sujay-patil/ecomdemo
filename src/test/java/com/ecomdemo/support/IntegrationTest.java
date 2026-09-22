package com.ecomdemo.support;

import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
 * lets the three integration tests share a single context — and therefore a single container and
 * a single PostgreSQL boot. Add a {@code @MockitoBean} or a stray {@code @TestPropertySource} to
 * one subclass and that subclass silently gets a context, and a container, of its own.
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
 * <p>Since Phase 8 most of the API needs credentials, and these tests send real ones — a real
 * {@code Authorization} header, verified against a real BCrypt hash in the container's database,
 * by the real filter chain. That is the whole point of running at this level: {@code @WithMockUser}
 * would skip the authentication it is supposed to be exercising. {@link #rest} stays
 * unauthenticated so that the "anonymous callers are refused" cases can still be written.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(PostgresContainerConfig.class)
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

    /**
     * A client authenticated as the seeded ADMIN.
     *
     * <p>{@code withBasicAuth} does not log in; it returns a copy of the template that attaches
     * an {@code Authorization: Basic ...} header to every request. The login then happens on
     * each call, which is exactly how a stateless API behaves.
     */
    protected TestRestTemplate asAdmin() {
        return rest.withBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    /**
     * Registers a CUSTOMER (if this container has not seen it yet) and returns a client
     * authenticated as it.
     *
     * <p>Registration goes through the public endpoint rather than straight into the database,
     * so the password really is hashed by the application and really is verified back through
     * the {@code PasswordEncoder} on the next call. A repeat run inside the same container gets
     * a 409, which is fine: the account is there either way and the password has not changed.
     *
     * <p>Each test class uses a username of its own, because an account now owns a cart — two
     * classes sharing a name would share a cart and interfere with each other.
     */
    protected TestRestTemplate asCustomer(String username) {
        rest.postForEntity(
                "/api/customers/register",
                new RegisterRequest(username, IT_PASSWORD, username + " (integration test)"),
                CustomerResponse.class);
        return rest.withBasicAuth(username, IT_PASSWORD);
    }
}

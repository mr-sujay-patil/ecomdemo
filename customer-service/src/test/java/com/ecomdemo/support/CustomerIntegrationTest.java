package com.ecomdemo.support;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.auth.dto.TokenResponse;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * The base for customer-service's integration tests.
 *
 * <p><strong>This is the one extracted service whose test base can still LOG IN</strong>, and the
 * contrast with {@code CatalogIntegrationTest} is the boundary showing through the tests. That base
 * mints a service token from the shared secret, because catalog-service has no login endpoint and a
 * test that logged in there would be proving something about a service that does not exist. Here,
 * logging in is exactly what the service is for — so these tests use the real endpoint, with real
 * credentials, and get a real token back.
 *
 * <p>It is also the only test base that can afford to: the seeded administrator is in this service's
 * own V1 migration.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(PostgresContainerConfig.class)
@ActiveProfiles("it")
public abstract class CustomerIntegrationTest {

    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "admin123";
    protected static final String IT_PASSWORD = "integration-test-password";

    @Autowired
    protected TestRestTemplate rest;

    protected TestRestTemplate asAdmin() {
        return withToken(login(ADMIN_USERNAME, ADMIN_PASSWORD));
    }

    /** Registers the account if it is not there yet, then logs in as it. */
    protected TestRestTemplate asCustomer(String username) {
        rest.postForEntity("/api/customers/register",
                new RegisterRequest(username, IT_PASSWORD, username + " the tester"),
                CustomerResponse.class);
        return withToken(login(username, IT_PASSWORD));
    }

    /** Exchanges credentials for a token over real HTTP, and fails the test if that does not work. */
    protected String login(String username, String password) {
        TokenResponse token = rest.postForObject(
                "/api/auth/login", new LoginRequest(username, password), TokenResponse.class);
        Assertions.assertThat(token).as("login as %s should return a token", username).isNotNull();
        Assertions.assertThat(token.accessToken()).isNotBlank();
        return token.accessToken();
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

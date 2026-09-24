package com.ecomdemo.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The correlation ID over real HTTP, through the real filter chain.
 *
 * <p>{@code CorrelationIdFilterTest} already proves what the filter does when it is called. What
 * it cannot prove is that the filter is REGISTERED, and registered in the right place — a
 * {@code @Component} implementing {@code Filter} is picked up by Boot automatically, and the
 * {@code @Order} that puts it ahead of Spring Security is an annotation with nothing checking it.
 * The interesting case is therefore the 401 below: it is answered inside the security chain and
 * never reaches a controller, so it only carries a header if the ordering is genuinely right.
 */
@DisplayName("X-Correlation-Id over HTTP")
class CorrelationIdApiIT extends IntegrationTest {

    private ResponseEntity<String> get(String path, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        if (correlationId != null) {
            headers.set(CorrelationId.HEADER, correlationId);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("every response carries one, including a public GET")
    void publicEndpointsCarryTheHeader() {
        ResponseEntity<String> response = get("/api/products", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER))
                .isNotNull()
                .matches(CorrelationId.ALLOWED);
    }

    @Test
    @DisplayName("a request refused with 401 carries one too")
    void rejectedRequestsCarryTheHeader() {
        ResponseEntity<String> response = get("/api/cart", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER))
                .as("a 401 is answered by the security chain; the header proves this filter runs before it")
                .isNotNull()
                .matches(CorrelationId.ALLOWED);
    }

    @Test
    @DisplayName("a request refused with 403 carries one too")
    void forbiddenRequestsCarryTheHeader() {
        // An ADMIN has no cart on purpose (see SecurityConfig), so this is a genuine 403 from the
        // authorization rules rather than a contrived one.
        TestRestTemplate admin = asAdmin();

        ResponseEntity<String> response = admin.getForEntity("/api/cart", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER)).isNotNull();
    }

    @Test
    @DisplayName("a 404 carries one, which is the response most often being investigated")
    void notFoundCarriesTheHeader() {
        ResponseEntity<String> response = get("/api/products/99999999", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER)).isNotNull();
    }

    @Test
    @DisplayName("the caller's own ID is echoed back unchanged")
    void echoesTheCallersId() {
        ResponseEntity<String> response = get("/api/products", "customer-support-ticket-8814");

        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER))
                .isEqualTo("customer-support-ticket-8814");
    }

    @Test
    @DisplayName("an unsafe ID is replaced rather than echoed or rejected")
    void replacesAnUnsafeId() {
        String unsafe = "../../etc/passwd";

        ResponseEntity<String> response = get("/api/products", unsafe);

        // Not a 400: the ID exists for our diagnostics, and failing a client's request over it
        // would make a logging feature into an availability problem.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER))
                .isNotEqualTo(unsafe)
                .matches(CorrelationId.ALLOWED);
    }

    @Test
    @DisplayName("two requests get two IDs")
    void eachRequestGetsItsOwnId() {
        String first = get("/api/products", null).getHeaders().getFirst(CorrelationId.HEADER);
        String second = get("/api/products", null).getHeaders().getFirst(CorrelationId.HEADER);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("Actuator's endpoints carry one as well, even though they are not request-logged")
    void actuatorCarriesTheHeader() {
        // RequestLogFilter skips /actuator; CorrelationIdFilter deliberately does not. A probe
        // that fails is a thing to investigate, and whatever the application logged while
        // answering it should be findable.
        ResponseEntity<String> response = get("/actuator/health", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER)).isNotNull();
    }
}

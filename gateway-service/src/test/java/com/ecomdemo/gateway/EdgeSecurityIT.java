package com.ecomdemo.gateway;

import com.ecomdemo.gateway.support.GatewayTest;
import com.ecomdemo.logging.CorrelationId;
import com.ecomdemo.shared.AuthMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * What the edge permits, refuses, and says while refusing.
 *
 * <p><strong>An {@code IT}, because it starts a Redis container.</strong> The rate limiter is a default
 * filter on every route, so every request through this application consults Redis — which makes this a
 * test that needs Docker, and this project runs those in Failsafe. Naming it {@code *Test} would have
 * put a container start into Surefire, where a developer without Docker gets a failure from the unit
 * suite.
 *
 * <p><strong>The bodies are asserted, not just the statuses.</strong> Phase 20d found two services
 * answering a rejected token with {@code 401} and an <em>empty body</em>, because the resource server
 * has an {@code authenticationEntryPoint} of its own that nobody had set. The status was right in that
 * defect too — which is exactly why a test that stops at the status code would have shipped it again,
 * and this time at the front door for every endpoint at once.
 */
@DisplayName("Security at the edge")
class EdgeSecurityIT extends GatewayTest {

    @Test
    @DisplayName("no token on a protected path returns 401 and an ApiError saying how to log in")
    void anonymousIsRefusedWithAnApiError() {
        web.get().uri("/api/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.UNAUTHORIZED.value())
                .jsonPath("$.message").isEqualTo(AuthMessages.NO_TOKEN);
    }

    /**
     * A token the gateway cannot trust, and the distinction that matters.
     *
     * <p>"Log in" and "log in AGAIN" are different answers to different situations, and the caller
     * can tell them apart — a client retrying with the same expired token learns something from the
     * second message that the first would not have told it.
     */
    @Test
    @DisplayName("a tampered token returns 401 and the invalid-token message, not an empty body")
    void aTamperedTokenIsRefusedWithAnApiError() {
        String tampered = tokenWithRoles("shopper", "CUSTOMER") + "x";

        web.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.UNAUTHORIZED.value())
                .jsonPath("$.message").isEqualTo(AuthMessages.INVALID_TOKEN);
    }

    @Test
    @DisplayName("a customer reaching an admin path returns 403 and an ApiError")
    void theWrongRoleIsRefusedWithAnApiError() {
        web.post().uri("/api/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("shopper", "CUSTOMER"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.FORBIDDEN.value())
                .jsonPath("$.message").value(message ->
                        org.assertj.core.api.Assertions.assertThat((String) message)
                                .contains("does not have permission"));
    }

    /**
     * The anonymous doors, and the proof that they are doors rather than holes.
     *
     * <p><strong>A 5xx is the PASS here</strong>, which reads oddly until you see what it rules out.
     * The request was authorised, matched a route, and failed only because no catalogue service is
     * listening in this test. What matters is that it is not a 401 and not a 403: everything up to the
     * proxy call is the gateway's business, and everything after it is not.
     *
     * <p>It is asserted as 5xx rather than as one code because the exact code is the gateway's choice
     * about somebody else's outage — measured here as 500, where 503 would be the more informative
     * answer. Pinning it would turn a test of authorisation into a test of that unrelated choice.
     */
    @Test
    @DisplayName("anonymous catalogue reads are let through to the route")
    void anonymousMayReadTheCatalogue() {
        web.get().uri("/api/products")
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("login is reachable without a token, or nobody could ever get one")
    void loginIsAnonymous() {
        web.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"someone\",\"password\":\"whatever\"}")
                .exchange()
                // 5xx, not 401: the door is open and the room behind it is empty in this test. If
                // login ever required a token, THIS is the test that would fail.
                .expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("stock is ADMIN-only now that it is reachable from outside at all")
    void inventoryRequiresAdmin() {
        web.get().uri("/api/inventory/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("shopper", "CUSTOMER"))
                .exchange()
                .expectStatus().isForbidden();

        web.get().uri("/api/inventory/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("boss", "ADMIN"))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("the gateway's own health is open, and its other endpoints are not")
    void actuatorIsExposedTheSameWayEveryServiceExposesIt() {
        web.get().uri("/actuator/health").exchange().expectStatus().isOk();
        web.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
        web.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }

    /**
     * The correlation ID is minted at the edge even for a request that never reaches a service.
     *
     * <p>A rejected request is the one somebody is most likely to be investigating, so it is the one
     * that most needs an ID in its response. This is why the filter runs ahead of Spring Security
     * rather than behind it.
     */
    @Test
    @DisplayName("a 401 still comes back with a correlation id, because that is when you need it")
    void aRejectedRequestStillCarriesACorrelationId() {
        web.get().uri("/api/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(CorrelationId.HEADER);
    }

    @Test
    @DisplayName("a correlation id the caller sent is kept, not replaced")
    void aCallerSuppliedCorrelationIdSurvives() {
        String supplied = "smoke-test-correlation-1234";

        web.get().uri("/api/orders")
                .header(CorrelationId.HEADER, supplied)
                .exchange()
                .expectHeader().valueEquals(CorrelationId.HEADER, supplied);
    }

    /**
     * And a malformed one is not.
     *
     * <p>{@code CorrelationId.sanitize} replaces anything that does not match its pattern, because an
     * unvalidated header written into a log line is a log injection hole — Loki would index the
     * forgery as happily as the real thing.
     *
     * <p><strong>The first version of this test could not run, and the reason is worth keeping.</strong>
     * It sent {@code "not valid\nlevel=ERROR injected"} — the actual injection attempt — and the HTTP
     * client refused to transmit it: a header value containing a newline is rejected before it reaches
     * any server. So the control-character case is defended by the protocol stack, not by this
     * pattern, and a test asserting otherwise would have been testing the client. What sanitize is
     * genuinely for is the value that IS transmissible and still must not be trusted: too short, too
     * long, or carrying punctuation that a log parser would read as structure.
     */
    @Test
    @DisplayName("a malformed correlation id is replaced rather than echoed")
    void aForgedCorrelationIdIsSanitized() {
        String forged = "no";

        web.get().uri("/api/orders")
                .header(CorrelationId.HEADER, forged)
                .exchange()
                .expectHeader().value(CorrelationId.HEADER, value ->
                        org.assertj.core.api.Assertions.assertThat(value)
                                .isNotEqualTo(forged)
                                .matches(CorrelationId.ALLOWED.pattern()));
    }
}

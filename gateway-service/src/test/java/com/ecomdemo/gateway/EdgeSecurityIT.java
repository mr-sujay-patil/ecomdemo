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

    /**
     * The two claims that came here from {@code ProductProxyAccessTest} when the application's
     * {@code /api/products} proxy was deleted.
     *
     * <p>That class proved the catalogue's access rules at the application's edge, which was the only
     * edge there was. Both claims are the same; the owner changed. They are asserted here BEFORE the
     * old class was removed, so the coverage was never absent even momentarily.
     */
    @Test
    @DisplayName("writing the catalogue anonymously is 401, and never reaches catalog-service")
    void writingTheCatalogueAnonymouslyIsRefused() {
        web.post().uri("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Lamp\",\"price\":9.99}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.UNAUTHORIZED.value())
                .jsonPath("$.message").isEqualTo(AuthMessages.NO_TOKEN);
    }

    /** Phase 34: a browser {@code <img>} cannot send a bearer token, so the image path must be as public as the list. */
    @Test
    @DisplayName("a product image is readable anonymously, and nobody but an ADMIN may write to that path")
    void productImagesArePublicToRead() {
        web.get().uri("/api/products/1/image")
                .exchange()
                .expectStatus().is5xxServerError();

        web.put().uri("/api/products/1/image")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("shopper", "CUSTOMER"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("but a customer may still browse - opening writes to ADMIN closed nothing else")
    void aCustomerMayStillBrowseTheCatalogue() {
        web.get().uri("/api/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("shopper", "CUSTOMER"))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    /**
     * Phase 28. The semantic search sits under the public GET rule; the backfill's status sits under
     * the same prefix and must NOT - the rule for it has to come before the public one.
     */
    @Test
    @DisplayName("semantic search is public, the embedding backfill's status is ADMIN-only")
    void searchIsPublicButTheBackfillIsNot() {
        web.get().uri("/api/products/search?q=laptop")
                .exchange()
                .expectStatus().is5xxServerError();

        web.get().uri("/api/products/embeddings/backfill/1")
                .exchange()
                .expectStatus().isUnauthorized();
        web.get().uri("/api/products/embeddings/backfill/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("shopper", "CUSTOMER"))
                .exchange()
                .expectStatus().isForbidden();
        web.get().uri("/api/products/embeddings/backfill/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("boss", "ADMIN"))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    /**
     * Phase 29. The assistant reads the caller's own cart and orders, so only a customer may use it:
     * an administrator has neither, and anonymous has no token to forward.
     */
    @Test
    @DisplayName("the shopping assistant is for customers only")
    void theAssistantIsForCustomersOnly() {
        web.post().uri("/api/assistant/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"hello\"}")
                .exchange()
                .expectStatus().isUnauthorized();
        web.post().uri("/api/assistant/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("boss", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"hello\"}")
                .exchange()
                .expectStatus().isForbidden();
        // 5xx: let through, to a route whose upstream is closed in this test.
        web.post().uri("/api/assistant/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles("shopper", "CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"hello\"}")
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

    /**
     * KI-006. The gateway's actuator lives on its own management port, which no client can reach
     * (compose does not publish it, the Ingress does not route it). The public port answers nothing
     * under {@code /actuator}: metrics describe route names, error rates and the JVM, and the port
     * every client uses is the wrong place for them to be open.
     */
    @Test
    @DisplayName("the public port serves no actuator endpoint at all")
    void thePublicPortDoesNotServeTheActuator() {
        web.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        web.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
        web.get().uri("/actuator/health/readiness").exchange().expectStatus().isUnauthorized();
        web.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the management port serves health and metrics, and no other endpoint")
    void theManagementPortServesHealthAndMetricsAndNoOtherEndpoint() {
        management.get().uri("/actuator/health").exchange().expectStatus().isOk();
        management.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        management.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
        management.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
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

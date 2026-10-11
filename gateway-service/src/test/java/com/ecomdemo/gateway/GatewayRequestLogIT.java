package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecomdemo.gateway.support.GatewayTest;
import com.ecomdemo.logging.CorrelationId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * KI-035: the gateway's own request log, through the real filter chain.
 *
 * <p><strong>Why this matters.</strong> A shopper shown an {@code X-Correlation-Id} on a 503 that the
 * gateway answered ITSELF (catalog-service down, the route's circuit breaker forwarding to
 * {@code /fallback/catalog}) hands that id to support. Before KI-035 the request had reached no service,
 * so no service logged it, and the gateway logged nothing at all: the id led nowhere. A 401, 403 or 429
 * decided at the edge had the same hole.
 *
 * <p><strong>What it must not say matters as much as what it says.</strong> These are the same secrecy
 * cases as common's {@code RequestLogFilterTest} (a bearer token, a query-string API key, a login
 * password), now asserted at the edge, where the login request actually passes. That turns the old
 * "the gateway logs no bodies" from an absence into a decision a test enforces.
 *
 * <p>Events are captured with a Logback {@link ListAppender} on the filter's own logger and picked out
 * by the {@code correlation_id} in their MDC, so a line from another request in the same JVM cannot be
 * mistaken for this one. The logger is looked up BY NAME so that, before the filter existed, this test
 * compiled and failed on its assertions (the regression evidence), rather than not compiling at all.
 *
 * <p>The line is written when the exchange completes, which can be a moment AFTER the client has read
 * the response; hence Awaitility rather than an immediate assertion (and never a sleep).
 */
@DisplayName("KI-035: the gateway's request log")
class GatewayRequestLogIT extends GatewayTest {

    private static final String LOGGER = "com.ecomdemo.gateway.RequestLogWebFilter";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheRequestLog() {
        logger = (Logger) LoggerFactory.getLogger(LOGGER);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> linesFor(String correlationId) {
        // A copy: the appender's list is appended to from the event loop while this reads it.
        synchronized (appender.list) {
            return appender.list.stream()
                    .filter(event -> correlationId.equals(event.getMDCPropertyMap().get(CorrelationId.MDC_KEY)))
                    .toList();
        }
    }

    /** Waits for the line, then checks for a while that no second one follows (the fallback forward). */
    private ILoggingEvent theOnlyLineFor(String correlationId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> !linesFor(correlationId).isEmpty());
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> linesFor(correlationId).size() == 1);
        return linesFor(correlationId).get(0);
    }

    @Test
    @DisplayName("a 503 the gateway answered itself is logged once, with its correlation id, and quotes nothing")
    void theFallbackIsLoggedOnceAndQuotesNothing() {
        String id = "ki035-fallback-12345";
        String traceId = "5af7651916cd43dd8448eb211c80319d";
        String token = tokenWithRoles("alice", "CUSTOMER");

        // The route targets a closed port, so the catalogue read ends in the circuit breaker's
        // fallback: the 503 a shopper sees when catalog-service is down. A real (minted) token, because
        // an invalid one is refused 401 by the resource server even on a public path.
        web.get().uri("/api/products?api_key=leaked-in-a-url&page=2")
                .header(CorrelationId.HEADER, id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("traceparent", "00-" + traceId + "-b7ad6b7169203332-01")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(CorrelationId.HEADER, id);

        // ONE line: the forward to /fallback/catalog is dispatched inside the same exchange and must not
        // produce a second line for the same request (nor a line for the internal path).
        ILoggingEvent line = theOnlyLineFor(id);
        String message = line.getFormattedMessage();
        assertThat(message)
                .startsWith("GET /api/products -> 503 in ")
                .endsWith("ms")
                .doesNotContain("/fallback")
                .doesNotContain("leaked-in-a-url")
                .doesNotContain("api_key")
                .doesNotContain("Bearer")
                .doesNotContain(token);
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getMDCPropertyMap()).containsEntry(CorrelationId.MDC_KEY, id);
        // And the trace id, which Alloy also lifts into Loki: from this line, Grafana jumps to the trace.
        assertThat(line.getMDCPropertyMap()).containsEntry("traceId", traceId);
        assertThat(line.getMDCPropertyMap().values()).noneMatch(value -> value.contains(token));
    }

    @Test
    @DisplayName("a login is logged without its password, with the status the error handler gave it")
    void aLoginIsLoggedWithoutItsBody() {
        String id = "ki035-login-12345";

        // customer-service is not running (closed port): the proxy call fails with an exception that
        // escapes every WebFilter and is rendered by the error handler AFTER them. The line must still
        // carry the status the client actually got, not the "nothing set yet" of the moment the filter
        // chain ended.
        int status = web.post().uri("/api/auth/login")
                .header(CorrelationId.HEADER, id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"hunter2-ki035\"}")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();

        String message = theOnlyLineFor(id).getFormattedMessage();
        assertThat(message)
                .startsWith("POST /api/auth/login -> " + status + " in ")
                .doesNotContain("hunter2-ki035")
                .doesNotContain("password")
                .doesNotContain("alice");
    }

    @Test
    @DisplayName("a 401 refused at the edge is logged with its correlation id")
    void aRefusalIsLogged() {
        String id = "ki035-refused-12345";

        web.get().uri("/api/orders")
                .header(CorrelationId.HEADER, id)
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(theOnlyLineFor(id).getFormattedMessage()).startsWith("GET /api/orders -> 401 in ");
    }

    @Test
    @DisplayName("an id the gateway minted is the one in the line and in the response header")
    void aGeneratedIdIsTheOneLogged() {
        String minted = web.get().uri("/api/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst(CorrelationId.HEADER);

        assertThat(minted).matches(CorrelationId.ALLOWED.pattern());
        assertThat(theOnlyLineFor(minted).getFormattedMessage()).startsWith("GET /api/orders -> 401 in ");
    }

    @Test
    @DisplayName("says nothing about Actuator's own endpoints, on either port")
    void skipsActuator() {
        String onManagement = "ki035-actuator-mgmt-1";
        String onApi = "ki035-actuator-api-12";
        // A request that IS logged, sent last: the exchanges run one after another, so by the time its
        // line is there the actuator requests have long finished, and their silence is an answer.
        String marker = "ki035-actuator-marker";

        management.get().uri("/actuator/health").header(CorrelationId.HEADER, onManagement).exchange()
                .expectStatus().isOk();
        web.get().uri("/actuator/health").header(CorrelationId.HEADER, onApi).exchange();
        web.get().uri("/api/orders").header(CorrelationId.HEADER, marker).exchange();

        theOnlyLineFor(marker);
        assertThat(linesFor(onManagement)).isEmpty();
        assertThat(linesFor(onApi)).isEmpty();
    }
}

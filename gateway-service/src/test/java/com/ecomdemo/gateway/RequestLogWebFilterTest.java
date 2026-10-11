package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecomdemo.logging.CorrelationId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * KI-035: the edge cases of {@link RequestLogWebFilter} that a real request cannot be made to hit on
 * demand. {@code GatewayRequestLogIT} covers the real chain; this drives the filter with a mock
 * exchange, so the moment the response commits is in the test's hands.
 */
@DisplayName("RequestLogWebFilter")
class RequestLogWebFilterTest {

    private static final String ID = "ki035-unit-12345";

    private final RequestLogWebFilter filter = new RequestLogWebFilter();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogOutput() {
        logger = (Logger) LoggerFactory.getLogger(RequestLogWebFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(CorrelationIdWebFilter.ATTRIBUTE, ID);
        return exchange;
    }

    @Test
    @DisplayName("an error rendered after the chain is logged once, with the status it was rendered with")
    void waitsForTheStatusOfAnErrorRenderedLater() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/auth/login"));

        filter.filter(exchange, ignored -> Mono.error(new IllegalStateException("upstream refused")))
                .onErrorComplete()
                .block();

        // The chain has ended but nothing is decided yet: logging now would invent a status.
        assertThat(appender.list).isEmpty();

        // What the error handler outside every WebFilter does next.
        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        exchange.getResponse().setComplete().block();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).startsWith("POST /api/auth/login -> 500 in ");
            assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationId.MDC_KEY, ID);
        });
    }

    @Test
    @DisplayName("a response already committed is logged at once, without its query string")
    void logsACommittedResponseAtOnce() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/products?api_key=leaked-in-a-url"));

        filter.filter(exchange, committed -> {
            committed.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return committed.getResponse().setComplete();
        }).block();

        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .startsWith("GET /api/products -> 503 in ")
                .doesNotContain("leaked-in-a-url"));
    }

    @Test
    @DisplayName("a cancelled request is logged once, as cancelled")
    void logsACancelledRequest() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/products"));

        filter.filter(exchange, never -> Mono.never()).subscribe().dispose();

        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .startsWith("GET /api/products -> no status in ")
                .endsWith("ms (cancelled)"));
    }

    @Test
    @DisplayName("leaves the MDC as it found it")
    void restoresTheMdc() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/products"));

        filter.filter(exchange, done -> done.getResponse().setComplete()).block();

        // The event loop serves other requests next: an id left behind would be stamped on their lines.
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        assertThat(appender.list).hasSize(1);
    }

    @Test
    @DisplayName("says nothing about Actuator's own endpoints")
    void skipsActuator() {
        for (String path : List.of("/actuator/health", "/actuator/health/readiness", "/actuator/prometheus")) {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(path));
            filter.filter(exchange, done -> done.getResponse().setComplete()).block();
        }

        assertThat(appender.list).isEmpty();
    }
}

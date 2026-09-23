package com.ecomdemo.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * What the request log says, and — more importantly — what it does not say.
 *
 * <p>Asserted against a Logback {@link ListAppender} attached to the filter's own logger, so the
 * test sees the events themselves rather than formatted console output. The secrecy assertions
 * are the point of this class: they fail the build if somebody later adds the {@code
 * Authorization} header or the request body to the line "just while debugging", which is how
 * credentials end up in a log aggregator and then in every backup of it.
 */
@DisplayName("RequestLogFilter")
class RequestLogFilterTest {

    private final RequestLogFilter filter = new RequestLogFilter();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogOutput() {
        logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    @Test
    @DisplayName("logs one line per request with method, path and status")
    void logsTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(events()).hasSize(1);
        assertThat(events().get(0).getFormattedMessage())
                .contains("POST")
                .contains("/api/orders")
                .contains("201");
    }

    @Test
    @DisplayName("logs a request that was refused, which is the one being investigated")
    void logsARejectedRequest() throws Exception {
        // This is why the filter is ordered ahead of Spring Security. A 401 handled entirely
        // inside the security chain never reaches a controller, so a log written further in would
        // not exist — and "why is this client getting 401s" is among the most common questions a
        // log is asked.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cart");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(events().get(0).getFormattedMessage()).contains("401");
    }

    @Test
    @DisplayName("never logs the Authorization header or the request body")
    void logsNoCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.super-secret-token");
        request.addHeader("Cookie", "session=another-secret");
        request.setContent("{\"username\":\"alice\",\"password\":\"hunter2\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        String line = events().get(0).getFormattedMessage();
        assertThat(line)
                .doesNotContain("super-secret-token")
                .doesNotContain("hunter2")
                .doesNotContain("another-secret")
                .doesNotContain("Bearer");
    }

    @Test
    @DisplayName("never logs the query string, where API keys end up")
    void logsNoQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.setQueryString("api_key=leaked-in-a-url&page=2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(events().get(0).getFormattedMessage()).doesNotContain("leaked-in-a-url");
    }

    @Test
    @DisplayName("says nothing about Actuator's own endpoints")
    void skipsActuator() throws Exception {
        // Prometheus scrapes every 15 seconds and the container probes health every 10. Logging
        // those is thousands of lines a day describing nobody.
        for (String path : List.of("/actuator/health", "/actuator/health/readiness", "/actuator/prometheus")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("still logs the request when the chain throws")
    void logsAFailedRequest() {
        MockFilterChain exploding = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        filter.doFilter(
                                                new MockHttpServletRequest("POST", "/api/orders"),
                                                new MockHttpServletResponse(),
                                                exploding)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events()).hasSize(1);
        assertThat(events().get(0).getFormattedMessage()).contains("/api/orders");
    }
}

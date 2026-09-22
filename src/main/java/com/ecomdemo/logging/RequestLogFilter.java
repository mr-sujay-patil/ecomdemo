package com.ecomdemo.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One log line per request: what was called, how it ended, and how long it took.
 *
 * <p><strong>Why this exists at all when Phase 15 already measures requests.</strong> Metrics and
 * logs answer different questions and neither substitutes for the other. A Prometheus counter
 * says "seventeen requests returned 409 in the last five minutes" — aggregated, cheap, and
 * unable to tell you which seventeen. A log line says "THIS request, at this instant, with this
 * correlation ID, returned 409" — expensive per event, and the only thing that can be joined
 * back to the rest of one user's story. The dashboard finds the spike; the log explains it.
 *
 * <p><strong>What is deliberately not logged.</strong> No headers, no request body, no response
 * body, no query string. Each of those is a place where credentials and personal data live: the
 * {@code Authorization} header carries a bearer token that is as good as a password until it
 * expires, a login body carries the password itself, and a query string is where an API key ends
 * up when a client takes a shortcut. A log aggregator is the wrong place for all of it — logs are
 * replicated, retained after the data they describe is deleted, and readable by people who have
 * no business reading customer data. The rule this class follows is that a log line may describe
 * a request but must never quote it.
 *
 * <p><strong>Ordering.</strong> Just inside {@link CorrelationIdFilter} and still outside Spring
 * Security, so that a request rejected with 401 is logged with its status and its correlation ID
 * rather than vanishing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // System.nanoTime, not currentTimeMillis: this is a DURATION, and wall-clock time can
        // step backwards when NTP corrects the host, which produces negative request times that
        // are impossible to explain later.
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            // The status is read AFTER the chain, because that is when it exists. Note that this
            // is the status as the container currently has it: an exception propagating out of
            // the chain is still 200 here, and becomes 500 during the error dispatch that
            // follows. That dispatch produces its own line, which is why the two are not the
            // same log entry and neither is a lie.
            log.info(
                    "{} {} -> {} in {}ms",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
        }
    }

    /**
     * Skips Actuator's own endpoints.
     *
     * <p>Prometheus scrapes every fifteen seconds and the container probes health every ten, so
     * without this the busiest thing in the log is the monitoring of the thing being logged —
     * thousands of lines a day that describe no user and explain no incident, filling a retention
     * window that exists for the lines that do. It is the same judgement, for the same reason, as
     * the {@code MeterFilter} that drops Actuator URIs from the metrics registry in Phase 15.
     *
     * <p>The machinery still applies to them: {@link CorrelationIdFilter} runs first and is not
     * skipped, so a scrape or a probe still carries an ID in its response and in any line the
     * application itself writes while serving it.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }
}

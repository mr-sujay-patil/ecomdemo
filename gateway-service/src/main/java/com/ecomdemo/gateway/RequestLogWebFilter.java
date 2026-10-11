package com.ecomdemo.gateway;

import com.ecomdemo.logging.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * One log line per request at the edge: what was called, how it ended, how long it took, and the
 * request's correlation id (KI-035).
 *
 * <p><strong>Why the gateway needs its own.</strong> Every service behind it writes a line per request
 * ({@code RequestLogFilter} in common), but some answers never reach a service: a 401, 403 or 429
 * decided here, and above all the 503 from {@code /fallback/catalog} when catalog-service is down. Those
 * responses carry an {@code X-Correlation-Id}, and before this filter the id led nowhere, because
 * nothing had logged the request at all. {@code RequestLogFilter} cannot simply be reused: it is servlet
 * code, and this is a reactive application (the servlet logging beans are not even scanned here).
 *
 * <p><strong>The same rule as the services: describe a request, never quote it.</strong> The line has
 * the method, the path WITHOUT its query string, the final status and the duration. No headers (the
 * {@code Authorization} header is a bearer token as good as a password until it expires), no body (the
 * login request, which passes through here, carries the password itself), no query string (where an
 * API key lands when a client takes a shortcut). This used to be true of the gateway by accident,
 * because it logged nothing; it is now a decision, and {@code GatewayRequestLogIT} enforces it.
 *
 * <p><strong>A {@link WebFilter}, not a gateway {@code GlobalFilter}.</strong> A global filter runs
 * only for a request that matched a route, after Spring Security. A WebFilter wraps everything: the
 * security chain's refusals, the rate limiter's 429, unrouted paths and the fallback. Ordered just
 * inside {@link CorrelationIdWebFilter} (so the id exists) and outside Spring Security (at -100), for
 * the same reason the servlet filter is: the refused request is the one somebody is investigating.
 *
 * <p><strong>Why the MDC is set by hand, around one call.</strong> The services put the correlation id
 * in SLF4J's MDC for the whole request, because a servlet request owns one thread. A reactive request
 * hops between event-loop threads, so a value put on one thread for the whole request would be missing
 * on the next and left behind for some other request (see {@link CorrelationIdWebFilter}). Here the
 * id is put on the thread for exactly the one synchronous {@code log.info} call, and the previous value
 * restored straight after, so it can neither be lost nor leak. With ECS logging the MDC entry becomes the
 * top-level JSON field {@code correlation_id}, which Alloy already turns into Loki structured metadata.
 *
 * <p><strong>When the line is written.</strong> When the filter chain finishes ({@code doFinally}),
 * with the status the response has then. One case needs care: an exception that escapes the chain
 * (for example a route whose upstream refuses the connection) has NO status of its own; the error
 * handler that runs outside every WebFilter decides it. {@code doFinally} runs after the terminal
 * signal has been passed downstream, so when that handler renders synchronously (as Boot's does today)
 * the response is already committed with its final status and is logged at once. When it is not yet
 * committed (rendering that is still in flight, or a handler that set nothing and leaves the server to
 * complete the response), the line is deferred to the moment it commits ({@code beforeCommit}), which
 * is when the final status exists. {@code RequestLogWebFilterTest} drives that path directly. A request that is
 * cancelled (the client went away) is logged at once, marked {@code (cancelled)}. Each path logs exactly once.
 *
 * <p>The fallback forward does not produce a second line: Spring Cloud Gateway dispatches
 * {@code forward:/fallback/catalog} straight to the {@code DispatcherHandler} inside the same exchange,
 * without passing the WebFilter chain again. The line therefore names the path the client asked for,
 * {@code /api/products}, which is the one the client will quote.
 */
@Component
class RequestLogWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogWebFilter.class);

    /** Same prefix as {@code RequestLogFilter} and the tracing predicate: Boot's default on both stacks. */
    static final String ACTUATOR_PATH = TracingConfig.ACTUATOR_PATH;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // The path alone: RequestPath has no query string, which is the point.
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(ACTUATOR_PATH)) {
            // Probes and scrapes: thousands of lines a day that describe nobody (the same judgement as
            // RequestLogFilter and the tracing predicate).
            return chain.filter(exchange);
        }

        // nanoTime, not currentTimeMillis: a duration, and the wall clock can step backwards.
        long startedAt = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> whenFinished(exchange, signal, startedAt));
    }

    private void whenFinished(ServerWebExchange exchange, SignalType signal, long startedAt) {
        ServerHttpResponse response = exchange.getResponse();
        if (signal == SignalType.CANCEL) {
            write(exchange, response.getStatusCode(), startedAt, true);
        } else if (response.isCommitted()) {
            write(exchange, response.getStatusCode(), startedAt, false);
        } else {
            // The status is still to be decided (an error the error handler has yet to render, or a
            // handler that set nothing): log at commit, when it is final.
            response.beforeCommit(() -> {
                write(exchange, response.getStatusCode(), startedAt, false);
                return Mono.empty();
            });
        }
    }

    private void write(ServerWebExchange exchange, HttpStatusCode status, long startedAt, boolean cancelled) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);

        String previous = MDC.get(CorrelationId.MDC_KEY);
        if (correlationId != null) {
            MDC.put(CorrelationId.MDC_KEY, correlationId);
        }
        try {
            // A committed response with no explicit status is a 200: that is the HTTP default the
            // server writes, and the number the client saw.
            String shown = status != null ? String.valueOf(status.value()) : cancelled ? "no status" : "200";
            if (cancelled) {
                log.info("{} {} -> {} in {}ms (cancelled)",
                        request.getMethod(), request.getPath().value(), shown, durationMs);
            } else {
                log.info("{} {} -> {} in {}ms", request.getMethod(), request.getPath().value(), shown, durationMs);
            }
        } finally {
            if (previous != null) {
                MDC.put(CorrelationId.MDC_KEY, previous);
            } else {
                MDC.remove(CorrelationId.MDC_KEY);
            }
        }
    }
}

package com.ecomdemo.gateway;

import com.ecomdemo.logging.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Stamps every request with a correlation ID at the true edge, and passes it on.
 *
 * <p><strong>This is not a new idea, and deliberately not a new spelling.</strong>
 * {@link CorrelationId} has defined the header {@code X-Correlation-Id} and the MDC key
 * {@code correlation_id} since Phase 16, and {@code CorrelationIdFilter} implements it in every
 * service. That filter is a servlet {@code OncePerRequestFilter} and does nothing here. What moves
 * in this phase is not the concept but the <em>origin</em>: until now the first service a request
 * happened to reach minted the ID, and a request that fanned out could be stamped twice. Now the
 * gateway mints it and every service downstream honours what it already sanitizes on the way in.
 *
 * <p>An ID the caller sent is reused rather than replaced, and {@link CorrelationId#sanitize} is
 * what makes accepting it safe — an unvalidated header written into a log line is a log injection
 * hole, and Loki would happily index the forgery.
 *
 * <p><strong>Why there is no MDC here.</strong> The servlet filter puts the ID in SLF4J's MDC,
 * which is a {@link ThreadLocal}, and clears it in a {@code finally}. Neither half works in a
 * reactive chain: one request is served by several event-loop threads in turn, so a value put on
 * the thread that started it is invisible to the thread that finishes it, and the {@code finally}
 * would run while the request is still in flight. Reactor's {@code Context} is the reactive
 * equivalent, and wiring it into logging is a real piece of work — deferred honestly rather than
 * faked with an MDC call that would be empty half the time and misattributed the other half.
 *
 * <p>What the gateway needs for its own logs, it gets from the access log's request headers. What
 * the <em>services</em> need, they get from the request header set below — and their MDC works,
 * because they are servlet applications with a thread per request.
 *
 * <p><strong>Why the response header is set through {@code beforeCommit}.</strong> A reactive
 * response's headers are mutable only until it commits, and the body may start streaming long
 * before this filter's {@code Mono} completes. Registering the write as a pre-commit action puts it
 * at the last moment that is still in time, on every path — including the error paths, which are
 * the ones anybody investigating actually cares about.
 */
@Component
class CorrelationIdWebFilter implements WebFilter, Ordered {

    /**
     * Ahead of Spring Security, which registers its chain at {@code -100}.
     *
     * <p>Running after it would mean a request rejected with 401 or 403 never reaches this filter —
     * so exactly the requests somebody is most likely to be investigating would come back with no
     * correlation header, and reach no service that could log one.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId =
                CorrelationId.sanitize(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));

        // Rewrite the REQUEST header so every service behind the gateway sees the same value -
        // including the case where the caller sent nothing and this is the only place it exists.
        ServerWebExchange stamped = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(CorrelationId.HEADER, correlationId)))
                .build();

        stamped.getResponse().beforeCommit(() -> {
            stamped.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(stamped);
    }
}

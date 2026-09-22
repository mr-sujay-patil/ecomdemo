package com.ecomdemo.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID, puts it where the logging framework will find it, and hands it back
 * to the caller.
 *
 * <p><strong>MDC in one paragraph.</strong> The Mapped Diagnostic Context is a
 * {@code Map<String, String>} held in a {@link ThreadLocal} by SLF4J. Anything put into it is
 * attached automatically to every log line written by that thread, by any class, without those
 * classes knowing this filter exists — which is the entire point. The alternative is threading a
 * correlation ID through every method signature in the application so that each {@code log.info}
 * can append it, and that alternative is why almost nobody does correlation by hand.
 *
 * <p><strong>The {@code finally} block is the whole class.</strong> Thread-local state on a
 * pooled thread is not cleaned up by anyone else: Tomcat hands the thread straight to the next
 * request, which would inherit the previous request's ID and quietly file its log lines under
 * somebody else's story. That is worse than having no ID at all, because it is wrong rather than
 * absent, and nothing about the output looks suspicious. {@link MDC#remove} on the way out, on
 * every path including an exception, is mandatory.
 *
 * <p><strong>Why this filter runs first.</strong> {@link Ordered#HIGHEST_PRECEDENCE} puts it
 * ahead of Spring Security's chain, which registers at order {@code -100}. Running after it would
 * mean a request rejected with 401 or 403 never reaches this code — so exactly the requests
 * somebody is most likely to be investigating would be the ones with no ID in their log lines and
 * no header in their response.
 *
 * <p><strong>Why the header is set before the chain runs.</strong> A servlet response can only
 * carry headers until the first byte of the body is written; after that the status line and
 * headers are already on the wire and {@code setHeader} is silently ignored. Setting it on the
 * way in means the ID is there whatever the rest of the chain does, including the error paths
 * that write a body from deep inside Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // An ID the caller sent is REUSED rather than replaced, which is what makes the ID work
        // across a boundary: a browser, a load test or (from Phase 20) a calling service can
        // stamp one request and then find every service's logs for it under the same value. It
        // is sanitized first — see CorrelationId.ALLOWED for why accepting it verbatim would be
        // a log injection hole.
        String correlationId = CorrelationId.sanitize(request.getHeader(CorrelationId.HEADER));

        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /**
     * Runs for error dispatches too.
     *
     * <p>{@link OncePerRequestFilter} skips the {@code ERROR} dispatch by default, so a request
     * that ends in an unhandled exception would have its final, most interesting log lines —
     * those written while rendering {@code /error} — emitted with an empty MDC. The
     * {@code finally} above has already cleared it by then, and the error dispatch is a second
     * pass through the filter chain on the same thread.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}

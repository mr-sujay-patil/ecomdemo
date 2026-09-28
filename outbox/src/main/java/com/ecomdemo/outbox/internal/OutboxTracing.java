package com.ecomdemo.outbox.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the outbox's gap between writing an event and publishing it.
 *
 * <h2>Why the outbox breaks a trace</h2>
 *
 * <p>Trace context is thread-bound: while a request is being handled, "the current span" is a
 * thread-local, and every HTTP call or Kafka send made on that thread picks it up and writes it
 * into the outgoing {@code traceparent} header. The outbox exists precisely to NOT send on that
 * thread (Phase 18: the order and the event commit together, the send happens later). So by the
 * time the relay publishes, on a scheduler thread, there is no current span, and the Kafka send
 * would begin a trace of its own. One checkout would appear in Tempo as two unrelated traces - the
 * request, and a disconnected "something was sent to orders.placed".
 *
 * <h2>The fix is the same one the outbox already uses for the payload</h2>
 *
 * <p>Write it down inside the transaction. {@link #currentTraceParent()} turns the current span into
 * its W3C {@code traceparent} text, which {@code OutboxWriter} stores in the row next to the
 * payload; {@link #inTraceOf} turns the stored text back into a parent and runs the send inside a
 * span that is its child. The Kafka template's own observation then sees that span as current and
 * writes a {@code traceparent} header that continues the ORIGINAL trace, so notification-service's
 * consumer span lands in the checkout's trace too.
 *
 * <p>This is exactly what the propagator does over HTTP, with a database row playing the part of
 * the header. {@link Propagator} is the tracing library's own "context to text and back" API, so
 * the format stays W3C Trace Context whatever the propagation settings say, and nothing here
 * parses a {@code traceparent} by hand.
 *
 * <h2>When nothing is traced</h2>
 *
 * <p>With a no-op tracer (unit tests pass {@link Tracer#NOOP}) there is never a current span, so
 * rows are written with a {@code null} {@code trace_parent} and published without a parent - the
 * pre-Phase-23 behaviour, and the right fallback if tracing is ever switched off.
 */
@Component
public class OutboxTracing {

    /** The W3C Trace Context header, and the only field that is stored. */
    static final String TRACEPARENT = "traceparent";

    /** The relay's span, a child of the request that wrote the row and the parent of the send. */
    static final String RELAY_SPAN = "outbox relay";

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * The current span as a W3C {@code traceparent}, or {@code null} when nothing is being traced.
     *
     * <p>An UNSAMPLED request still has a span and still yields a value, with the sampled bit of
     * its flags clear.
     * That is deliberate: storing it lets the relay honour the edge's "do not record" decision
     * instead of tossing a fresh coin and recording the Kafka half of a trace whose HTTP half was
     * never kept.
     */
    public String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Runs {@code action} inside a span that continues the trace an outbox row was written in.
     *
     * <p>A row without a {@code traceparent} runs with no span of its own: the send is then
     * traced exactly as before this class existed, as a root.
     */
    void inTraceOf(OutboxEvent event, Action action) throws Exception {
        String traceParent = event.getTraceParent();
        if (traceParent == null) {
            action.run();
            return;
        }

        Span span = propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get)
                .name(RELAY_SPAN)
                // What anyone will search Tempo by when a downstream service never heard of
                // something. Generic since Phase 24 (it was `order.id` while only orders used
                // the outbox); in the saga the aggregate id IS the order id in every service.
                .tag("outbox.event_id", event.getEventId().toString())
                .tag("outbox.event_type", event.getEventType())
                .tag("outbox.aggregate_type", event.getAggregateType())
                .tag("outbox.aggregate_id", event.getAggregateId())
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            action.run();
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** A send that may throw: the relay's {@code awaitAck} is checked-exception code. */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }
}

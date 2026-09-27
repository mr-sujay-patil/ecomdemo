package com.ecomdemo.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.messaging.OrderPlacedEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbox's trace hand-over, against the REAL OpenTelemetry SDK and W3C propagator.
 *
 * <p>Not mocks, because the claims are about a wire format: that what is stored is a genuine
 * {@code traceparent}, and that what the relay rebuilds from it is a child in the same trace.
 * A mocked propagator would test that this class calls a method; the real one tests that the
 * checkout and the Kafka send end up in one trace. The SDK is an in-memory library - there is no
 * collector here, only a list the finished spans are appended to.
 */
@DisplayName("OutboxTracing")
class OutboxTracingTest {

    /**
     * W3C Trace Context, version 00: trace id, span id, flags. The flags are a BIT FIELD, not a
     * yes/no: bit 0 is "sampled", and Trace Context Level 2 adds bit 1, "the trace id is random",
     * which OpenTelemetry sets. A sampled trace from this SDK therefore ends in {@code -03}, not
     * the {@code -01} most examples show - which is why this test reads the bit, not the text.
     */
    private static final String TRACEPARENT_FORMAT = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

    private final List<SpanData> finished = new CopyOnWriteArrayList<>();
    private SdkTracerProvider provider;
    private Tracer tracer;
    private OutboxTracing tracing;

    @BeforeEach
    void setUp() {
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter(finished)))
                .build();
        io.opentelemetry.api.trace.Tracer otel = provider.get("outbox-tracing-test");
        tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> { });
        OtelPropagator propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
        tracing = new OutboxTracing(tracer, propagator);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("outside any span there is nothing to store")
    void noSpanNoTraceParent() {
        assertThat(tracing.currentTraceParent()).isNull();
    }

    @Test
    @DisplayName("inside a span, stores that span as a W3C traceparent")
    void storesTheCurrentSpan() {
        Span request = tracer.nextSpan().name("http post /api/orders").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            String stored = tracing.currentTraceParent();
            assertThat(stored)
                    .matches(TRACEPARENT_FORMAT)
                    .startsWith("00-" + request.context().traceId() + "-"
                            + request.context().spanId() + "-");
            int flags = Integer.parseInt(stored.substring(53), 16);
            assertThat(flags & 0x01).as("the sampled bit").isEqualTo(1);
        } finally {
            request.end();
        }
    }

    @Test
    @DisplayName("the relay's send runs as a child of the request that wrote the row")
    void continuesTheStoredTrace() throws Exception {
        String traceId;
        String requestSpanId;
        String stored;
        Span request = tracer.nextSpan().name("http post /api/orders").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            traceId = request.context().traceId();
            requestSpanId = request.context().spanId();
            stored = tracing.currentTraceParent();
        } finally {
            request.end();
        }

        // Later, on another thread in production: nothing is in scope any more.
        assertThat(tracer.currentSpan()).isNull();
        OutboxEvent row = row(stored);
        AtomicReference<Span> duringSend = new AtomicReference<>();
        tracing.inTraceOf(row, () -> duringSend.set(tracer.currentSpan()));

        // What the Kafka template will see as current, and therefore propagate.
        assertThat(duringSend.get()).isNotNull();
        assertThat(duringSend.get().context().traceId()).isEqualTo(traceId);
        assertThat(duringSend.get().context().parentId()).isEqualTo(requestSpanId);
        // ...and nothing leaks onto the relay's thread afterwards.
        assertThat(tracer.currentSpan()).isNull();

        SpanData relay = finishedSpan(OutboxTracing.RELAY_SPAN);
        assertThat(relay.getTraceId()).isEqualTo(traceId);
        assertThat(relay.getParentSpanId()).isEqualTo(requestSpanId);
        assertThat(relay.getAttributes().get(AttributeKey.stringKey("order.id"))).isEqualTo("4812");
        assertThat(relay.getAttributes().get(AttributeKey.stringKey("outbox.event_id")))
                .isEqualTo(row.getEventId().toString());
    }

    @Test
    @DisplayName("a row without a traceparent is sent with no span of the relay's own")
    void rowWithoutTraceParent() throws Exception {
        AtomicReference<Span> duringSend = new AtomicReference<>();
        tracing.inTraceOf(row(null), () -> duringSend.set(tracer.currentSpan()));

        assertThat(duringSend.get()).isNull();
        assertThat(finished).isEmpty();
    }

    @Test
    @DisplayName("an unsampled request stays unsampled: the edge's decision is kept")
    void unsampledStaysUnsampled() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        OutboxEvent row = row("00-" + traceId + "-00f067aa0ba902b7-00");

        AtomicReference<Span> duringSend = new AtomicReference<>();
        tracing.inTraceOf(row, () -> duringSend.set(tracer.currentSpan()));

        // Still the same trace - the header a consumer receives carries the same id and "00" -
        // but nothing was recorded, so the Kafka half cannot appear without its HTTP half.
        assertThat(duringSend.get().context().traceId()).isEqualTo(traceId);
        assertThat(duringSend.get().context().sampled()).isFalse();
        assertThat(finished).isEmpty();
    }

    @Test
    @DisplayName("a failed send is rethrown, and the span records the failure")
    void failedSend() {
        OutboxEvent row = row("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThatThrownBy(() -> tracing.inTraceOf(row, () -> {
            throw new IllegalStateException("broker unreachable");
        })).isInstanceOf(IllegalStateException.class).hasMessage("broker unreachable");

        SpanData relay = finishedSpan(OutboxTracing.RELAY_SPAN);
        assertThat(relay.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(relay.hasEnded()).isTrue();
    }

    private static OutboxEvent row(String traceParent) {
        return new OutboxEvent(
                UUID.randomUUID(),
                "Order",
                "4812",
                OrderPlacedEvent.class.getSimpleName(),
                "{}",
                traceParent);
    }

    private SpanData finishedSpan(String name) {
        return finished.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finished span named '" + name + "'"));
    }

    /** The smallest possible exporter: finished spans go into a list the test can read. */
    private record CollectingExporter(List<SpanData> into) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            into.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}

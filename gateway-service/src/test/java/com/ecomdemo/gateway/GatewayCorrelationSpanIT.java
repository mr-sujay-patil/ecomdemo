package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.gateway.support.GatewayTest;
import com.ecomdemo.logging.CorrelationId;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;

/**
 * KI-035, the trace half: the gateway's SERVER span carries the request's correlation id, so the id a
 * user was shown finds the trace in Tempo with {@code { span.correlation_id = "<id>" }}.
 *
 * <p>The request is the one that matters most: a catalogue read that the gateway answers ITSELF from
 * {@code /fallback/catalog}. No service saw it, so the gateway's span is the whole trace.
 *
 * <p><strong>How spans are caught.</strong> Boot's OpenTelemetry auto-configuration hands every
 * {@link SpanExporter} bean to the SDK's span processor, so a collecting exporter declared here receives
 * exactly what an OTLP exporter would send to Tempo, through the real Micrometer-to-OpenTelemetry
 * bridge (the same idea as outbox's {@code OutboxTracingTest}, wired by Spring instead of by hand).
 * The processor batches, hence Awaitility.
 *
 * <p><strong>Sampling without touching a property.</strong> The sampler is parent-based: a request that
 * arrives with a W3C {@code traceparent} whose "sampled" flag is set is recorded whatever the
 * configured probability (0.1). Sending one also gives this test a trace id to find its own span by.
 */
@DisplayName("KI-035: the correlation id on the gateway's span")
class GatewayCorrelationSpanIT extends GatewayTest {

    private static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey(CorrelationId.MDC_KEY);

    @Autowired
    private CollectingExporter exporter;

    @Test
    @DisplayName("the SERVER span of a fallback 503 carries correlation_id")
    void theFallbackSpanCarriesTheCorrelationId() {
        String id = "ki035-span-1234567";
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        web.get().uri("/api/products")
                .header(CorrelationId.HEADER, id)
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(CorrelationId.HEADER, id);

        SpanData server = await().atMost(Duration.ofSeconds(30))
                .until(() -> exporter.serverSpanOf(traceId), Optional::isPresent)
                .orElseThrow();

        assertThat(server.getAttributes().get(CORRELATION_ID)).isEqualTo(id);
    }

    @Test
    @DisplayName("an id the gateway minted is the one on the span")
    void aMintedIdIsTheOneOnTheSpan() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";

        String minted = web.get().uri("/api/orders")
                .header("traceparent", "00-" + traceId + "-b7ad6b7169203331-01")
                .exchange()
                .expectStatus().isUnauthorized()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst(CorrelationId.HEADER);

        SpanData server = await().atMost(Duration.ofSeconds(30))
                .until(() -> exporter.serverSpanOf(traceId), Optional::isPresent)
                .orElseThrow();

        assertThat(server.getAttributes().get(CORRELATION_ID)).isEqualTo(minted);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CollectSpans {

        @Bean
        CollectingExporter collectingExporter() {
            return new CollectingExporter();
        }
    }

    static final class CollectingExporter implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        Optional<SpanData> serverSpanOf(String traceId) {
            return spans.stream()
                    .filter(span -> span.getKind() == SpanKind.SERVER && span.getTraceId().equals(traceId))
                    .findFirst();
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
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

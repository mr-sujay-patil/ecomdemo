package com.ecomdemo.logging;

import com.ecomdemo.support.ProjectRoot;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * What one log line actually looks like once it is JSON — and whether Alloy is reading the same
 * field names this produces.
 *
 * <p>The encoder is Spring Boot's own {@link StructuredLogEncoder}, driven here exactly as the
 * framework drives it when {@code logging.structured.format.console=ecs} is set: given a
 * {@link Environment} through the logger context and asked to encode an event. So this is the
 * real ECS document, not a hand-written approximation of one.
 *
 * <p><strong>Why the second half of this class exists.</strong> The JSON field names are a
 * contract with something outside the compiler's reach — {@code docker/alloy/config.alloy} names
 * them in JMESPath expressions, and a mismatch does not fail anything. Alloy carries on happily,
 * extracts nothing, and the correlation ID silently stops being searchable in Loki, which is
 * discovered during the next incident. This is the same reasoning, and the same kind of guard,
 * as {@code DashboardMetricsTest} in Phase 15: the build is the only place that can notice.
 */
@DisplayName("Structured (ECS) log output")
class StructuredLoggingTest {

    private static final Path ALLOY_CONFIG = ProjectRoot.resolve("docker/alloy/config.alloy");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Encodes one event the way the running application would, and parses the result. */
    private static JsonNode encode(String loggerName, Level level, String message, Map<String, String> mdc)
            throws IOException {
        LoggerContext loggerContext = new LoggerContext();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "ecomdemo");
        environment.setProperty("logging.structured.ecs.service.environment", "dev");
        // This is how the formatter finds the Environment in the real application too — Boot puts
        // it into the logger context during startup.
        loggerContext.putObject(Environment.class.getName(), environment);

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setFormat("ecs");
        encoder.setContext(loggerContext);
        encoder.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(loggerName);
        event.setLevel(level);
        event.setMessage(message);
        event.setThreadName("http-nio-8080-exec-1");
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(mdc);

        return JSON.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("the document")
    class TheDocument {

        @Test
        @DisplayName("carries the correlation ID as a top-level field, not a nested object")
        void carriesTheCorrelationId() throws Exception {
            JsonNode line =
                    encode(
                            "com.ecomdemo.order.internal.OrderService",
                            Level.INFO,
                            "order placed",
                            Map.of(CorrelationId.MDC_KEY, "abc123def456"));

            // Flat, because the MDC key has an underscore rather than a dot. A dotted key would
            // be written as {"correlation":{"id":"..."}} and every Loki query would have to
            // reach through the object — see the comment on CorrelationId.MDC_KEY.
            assertThat(line.path(CorrelationId.MDC_KEY).asText()).isEqualTo("abc123def456");
            assertThat(line.has("correlation")).isFalse();
        }

        @Test
        @DisplayName("names the level and the logger where ECS says they live")
        void usesEcsFieldNames() throws Exception {
            JsonNode line = encode("com.ecomdemo.order.internal.OrderService", Level.WARN, "stock is low", Map.of());

            assertThat(line.path("log").path("level").asText()).isEqualTo("WARN");
            assertThat(line.path("log").path("logger").asText()).isEqualTo("com.ecomdemo.order.internal.OrderService");
            assertThat(line.path("message").asText()).isEqualTo("stock is low");
            assertThat(line.path("@timestamp").asText()).isNotBlank();
            assertThat(line.path("ecs").path("version").asText()).isEqualTo("8.11");
        }

        @Test
        @DisplayName("identifies the service, so a second application cannot be confused with this one")
        void identifiesTheService() throws Exception {
            JsonNode line = encode("com.ecomdemo.order.internal.OrderService", Level.INFO, "hello", Map.of());

            assertThat(line.path("service").path("name").asText()).isEqualTo("ecomdemo");
            assertThat(line.path("service").path("environment").asText()).isEqualTo("dev");
        }

        @Test
        @DisplayName("is one line, because a log line that spans lines is two log entries")
        void isASingleLine() throws Exception {
            LoggerContext loggerContext = new LoggerContext();
            MockEnvironment environment = new MockEnvironment();
            loggerContext.putObject(Environment.class.getName(), environment);
            StructuredLogEncoder encoder = new StructuredLogEncoder();
            encoder.setFormat("ecs");
            encoder.setContext(loggerContext);
            encoder.start();

            LoggingEvent event = new LoggingEvent();
            event.setLoggerName("com.ecomdemo.Test");
            event.setLevel(Level.INFO);
            event.setMessage("a message\nwith an embedded newline");
            event.setTimeStamp(System.currentTimeMillis());
            event.setMDCPropertyMap(Map.of());

            String encoded = new String(encoder.encode(event), StandardCharsets.UTF_8);

            // Exactly one trailing newline and none inside: the newline in the message is escaped
            // by the JSON encoding. This is what makes a line-oriented shipper like Alloy able to
            // treat one line as one event without multiline stitching rules.
            assertThat(encoded).endsWith("\n");
            assertThat(encoded.strip()).doesNotContain("\n");
        }
    }

    @Nested
    @DisplayName("the Alloy pipeline reads the fields this actually produces")
    class AlloyAgrees {

        @Test
        @DisplayName("every JMESPath expression in config.alloy resolves in a real document")
        void alloyExpressionsResolve() throws Exception {
            JsonNode line =
                    encode(
                            "com.ecomdemo.logging.RequestLogFilter",
                            Level.INFO,
                            "GET /api/products -> 200 in 4ms",
                            Map.of(CorrelationId.MDC_KEY, "abc123def456"));
            String alloy = Files.readString(ALLOY_CONFIG);

            // The three extractions the pipeline depends on. Each is asserted to be PRESENT in
            // the config AND resolvable in the document, so renaming a field on either side
            // fails here rather than in production.
            assertThat(alloy).contains("level          = \"log.level\"");
            assertThat(line.path("log").path("level").isMissingNode()).isFalse();

            assertThat(alloy).contains("logger         = \"log.logger\"");
            assertThat(line.path("log").path("logger").isMissingNode()).isFalse();

            assertThat(alloy).contains("correlation_id = \"" + CorrelationId.MDC_KEY + "\"");
            assertThat(line.path(CorrelationId.MDC_KEY).isMissingNode()).isFalse();

            assertThat(alloy).contains("timestamp      = \"\\\"@timestamp\\\"\"");
            assertThat(line.path("@timestamp").isMissingNode()).isFalse();
        }

        @Test
        @DisplayName("promotes the level to a label and the correlation ID to structured metadata")
        void labelsTheCheapThingAndNotTheExpensiveOne() throws Exception {
            // Comments are stripped first. The comments in that file explain this very rule and
            // therefore contain the words it searches for, so a test that read them would be
            // asserting on its own documentation.
            String alloy = Files.readString(ALLOY_CONFIG)
                    .lines()
                    .filter(line -> !line.strip().startsWith("//"))
                    .collect(java.util.stream.Collectors.joining("\n"));

            // Cardinality is the whole game in Loki: a label becomes an indexed stream, so the
            // five-valued level belongs there and the one-value-per-request correlation ID does
            // not. If a later edit moves correlation_id into stage.labels, this fails — which is
            // the only warning anyone would get before the index started multiplying.
            String labels = alloy.substring(alloy.indexOf("stage.labels"), alloy.indexOf("stage.structured_metadata"));
            assertThat(labels).contains("level").doesNotContain(CorrelationId.MDC_KEY);

            String metadata = alloy.substring(alloy.indexOf("stage.structured_metadata"));
            assertThat(metadata).contains(CorrelationId.MDC_KEY);
        }
    }
}

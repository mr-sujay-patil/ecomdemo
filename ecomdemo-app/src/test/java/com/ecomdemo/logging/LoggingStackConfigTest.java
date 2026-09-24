package com.ecomdemo.logging;

import com.ecomdemo.support.ProjectRoot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four configuration files that have to agree with each other for a log line to become a
 * searchable one, checked against each other by the build.
 *
 * <p>None of these files is compiled, and none of the names they share is checked by anything at
 * runtime. Alloy pushes to whatever URL it was given; Loki accepts whatever labels arrive; Grafana
 * renders a dashboard whose datasource UID matches nothing as an empty panel with a small grey
 * message. Every mismatch fails silently and in the same way — no logs in Grafana — and each has a
 * different cause, which is a genuinely unpleasant thing to debug at the point somebody needs the
 * logs. This class turns each of those silent failures into a failing build.
 */
@DisplayName("The logging stack's configuration files agree")
class LoggingStackConfigTest {

    private static final Path COMPOSE = ProjectRoot.resolve("compose.yaml");
    private static final Path ALLOY = ProjectRoot.resolve("docker/alloy/config.alloy");
    private static final Path LOKI = ProjectRoot.resolve("docker/loki/loki.yaml");
    private static final Path DATASOURCE = ProjectRoot.resolve("docker/grafana/provisioning/datasources/loki.yml");
    private static final Path DASHBOARD = ProjectRoot.resolve("docker/grafana/dashboards/ecomdemo-logs.json");

    private static final String LOKI_DATASOURCE_UID = "ecomdemo-loki";

    @Nested
    @DisplayName("the application writes what Alloy expects to read")
    class Application {

        @Test
        @DisplayName("compose runs the container with ECS JSON logging")
        void composeSetsTheLogFormat() throws Exception {
            String compose = Files.readString(COMPOSE);

            // Without this the container logs the human-readable pattern layout, Alloy's
            // stage.json fails on every line, and the whole pipeline delivers unparsed text with
            // no level and no correlation ID — all of it looking like it is working.
            assertThat(compose).contains("LOG_FORMAT: ${LOG_FORMAT:-ecs}");
        }

        @Test
        @DisplayName("the property that consumes LOG_FORMAT is still there to consume it")
        void thePropertyReadsTheEnvironmentVariable() throws Exception {
            String properties = Files.readString(Path.of("src/main/resources/application.properties"));

            assertThat(properties).contains("logging.structured.format.console=${LOG_FORMAT:}");
        }
    }

    @Nested
    @DisplayName("Alloy pushes where Loki listens")
    class AlloyAndLoki {

        @Test
        @DisplayName("the push URL names the compose service and the configured port")
        void alloyPushesToTheLokiService() throws Exception {
            String alloy = Files.readString(ALLOY);
            String loki = Files.readString(LOKI);
            String compose = Files.readString(COMPOSE);

            assertThat(alloy).contains("url = \"http://loki:3100/loki/api/v1/push\"");
            // `loki` is a hostname only because compose names the service that.
            assertThat(compose).contains("  loki:");
            // ...and 3100 is only right because Loki is listening on it.
            assertThat(loki).contains("http_listen_port: 3100");
        }

        @Test
        @DisplayName("Alloy's health check runs under bash, because /dev/tcp is a bash builtin")
        void alloyHealthCheckUsesBash() throws Exception {
            String compose = Files.readString(COMPOSE);

            // /dev/tcp is not a device. There is no such file and the kernel knows nothing about
            // it; bash intercepts the name and opens a socket. Under `CMD-SHELL` the check runs
            // in /bin/sh, which in the Alloy image is dash - it answers "Directory nonexistent",
            // and the container is marked unhealthy for ever while shipping logs perfectly well.
            // That is what this line is guarding, and it cost 371 consecutive failed checks to
            // find, because a red health light on a container that works is easy to scroll past.
            assertThat(compose)
                    .contains("test: [\"CMD\", \"bash\", \"-c\", \"exec 3<>/dev/tcp/localhost/12345\"]");
            assertThat(compose).doesNotContain("CMD-SHELL\", \"exec 3<>/dev/tcp");
        }

        @Test
        @DisplayName("Alloy labels the application's stream `service_name=app`")
        void alloyLabelsTheStream() throws Exception {
            String alloy = Files.readString(ALLOY);
            String dashboard = Files.readString(DASHBOARD);

            assertThat(alloy).contains("target_label  = \"service_name\"");
            // Which is the selector every panel on the logs dashboard is written against.
            assertThat(dashboard).contains("service_name=\\\"app\\\"");
        }
    }

    @Nested
    @DisplayName("Loki keeps what it says it keeps")
    class LokiRetention {

        @Test
        @DisplayName("a retention period is paired with a compactor that enforces it")
        void retentionIsActuallyEnforced() throws Exception {
            String loki = Files.readString(LOKI);

            // The trap this guards: `retention_period` alone configures nothing. Loki deletes
            // old chunks only when the compactor is told to apply retention, so a config with
            // just the first line looks configured for seven days and keeps everything forever —
            // until the disk fills, which is usually how it is discovered.
            assertThat(loki).contains("retention_period: 168h");
            assertThat(loki).contains("retention_enabled: true");
        }
    }

    @Nested
    @DisplayName("Grafana can find the datasource and the datasource can find a correlation ID")
    class Grafana {

        @Test
        @DisplayName("the dashboard's panels reference the provisioned UID")
        void theDashboardBindsToTheDatasource() throws Exception {
            String datasource = Files.readString(DATASOURCE);
            JsonNode dashboard = new ObjectMapper().readTree(Files.readString(DASHBOARD));

            assertThat(datasource).contains("uid: " + LOKI_DATASOURCE_UID);

            // A dashboard that refers to a datasource by name, or by a UID Grafana generated on
            // some other machine, is the usual reason a provisioned dashboard comes up saying
            // "Datasource not found" — and there is nothing in the file to suggest why.
            assertThat(dashboard.path("panels")).isNotEmpty();
            dashboard
                    .path("panels")
                    .forEach(
                            panel ->
                                    assertThat(panel.path("datasource").path("uid").asText())
                                            .as("datasource uid of panel '%s'", panel.path("title").asText())
                                            .isEqualTo(LOKI_DATASOURCE_UID));
        }

        @Test
        @DisplayName("the derived field's regex matches an ID this application actually generates")
        void theDerivedFieldMatchesARealId() throws Exception {
            String datasource = Files.readString(DATASOURCE);

            // Pull the regex out of the YAML and run it against a genuine log line. The two are
            // written in different files, in different languages, by hand — this is exactly the
            // kind of agreement that quietly stops being true, and the symptom is a log line in
            // Grafana with no "All logs for this request" button and no explanation.
            java.util.regex.Matcher declaration =
                    Pattern.compile("matcherRegex: '(.+)'").matcher(datasource);
            assertThat(declaration.find()).as("a derived field regex in %s", DATASOURCE).isTrue();

            Pattern derivedField = Pattern.compile(declaration.group(1));
            String logLine = "{\"message\":\"hello\",\"" + CorrelationId.MDC_KEY + "\":\"" + CorrelationId.generate() + "\"}";

            assertThat(derivedField.matcher(logLine).find()).isTrue();
        }

        @Test
        @DisplayName("the correlation ID is queried as structured metadata, never as a label")
        void theDashboardQueriesMetadataNotLabels() throws Exception {
            String dashboard = Files.readString(DASHBOARD);

            // `| correlation_id = ...` after the stream selector is a structured-metadata filter.
            // `{correlation_id="..."}` inside the braces would be a label selector, which would
            // both fail (Alloy never sets that label) and, if somebody then "fixed" it by adding
            // the label, create one Loki stream per request.
            assertThat(dashboard).contains("| correlation_id");
            assertThat(dashboard).doesNotContain("{correlation_id=");
        }
    }
}

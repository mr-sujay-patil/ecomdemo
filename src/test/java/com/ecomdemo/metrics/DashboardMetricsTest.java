package com.ecomdemo.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the shipped dashboard and alert rule honest about the meters this application registers.
 *
 * <p><strong>The failure this exists to catch.</strong> A meter name is a public interface with no
 * compiler behind it. Rename {@code orders.placed} to {@code orders.completed} and everything
 * still builds, every test still passes, the application still starts — and the dashboard panel
 * quietly goes blank while {@code CheckoutConflictRateHigh} becomes an alert that can never fire
 * again. Nothing anywhere reports that, because from Prometheus' point of view the query is
 * perfectly valid and simply matches nothing. Monitoring fails silent by construction, which is
 * exactly backwards from how a test suite fails.
 *
 * <p>So this test reads the two files as TEXT — the same bytes Grafana and Prometheus are given —
 * pulls every {@code ecomdemo}-owned metric name out of the PromQL in them, and insists each one
 * is a series this application actually produces. It deliberately does not parse PromQL properly;
 * a regex over metric-name-shaped tokens is enough to catch a rename, which is the whole point.
 *
 * <p>Meters that Spring Boot contributes ({@code http_server_requests}, {@code jvm_*},
 * {@code hikaricp_*}, {@code process_*}) are checked against a registry rather than a list of our
 * own, so the test cannot be satisfied by adding a string to a constant somewhere.
 */
@DisplayName("the dashboard and alert rules refer to meters that exist")
class DashboardMetricsTest {

    private static final Path DASHBOARD = Path.of("docker/grafana/dashboards/ecomdemo.json");
    private static final Path ALERTS = Path.of("docker/prometheus/alerts.yml");
    private static final Path PROMETHEUS_CONFIG = Path.of("docker/prometheus/prometheus.yml");

    /**
     * Metric names Spring Boot's own instrumentation publishes, in Prometheus spelling.
     *
     * <p>These are not this application's to rename, so they are listed rather than derived. The
     * list is short on purpose: every entry is a panel somebody has to keep working across a
     * Spring Boot upgrade, and an upgrade that renames one of these should fail here rather than
     * in production.
     */
    private static final Set<String> SPRING_PROVIDED = Set.of(
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_bucket",
            "jvm_memory_used_bytes",
            "hikaricp_connections_active",
            "hikaricp_connections_idle",
            "hikaricp_connections_pending",
            "process_cpu_usage",
            "system_cpu_usage");

    /**
     * Anything that looks like a metric name in PromQL: a bare identifier not followed by a "(",
     * which is what separates {@code orders_placed_total} from {@code rate} or
     * {@code histogram_quantile}.
     */
    private static final Pattern METRIC_TOKEN =
            Pattern.compile("(?<![\\w:\"])([a-z][a-z0-9_]{3,}(?:_[a-z0-9]+)+)(?![\\w(])");

    /** PromQL keywords and Grafana/label words the pattern above cannot tell from a metric. */
    private static final Set<String> NOT_METRICS = Set.of(
            "rate_interval", "out_of_stock", "empty_cart", "order_id", "legend_format",
            "by_le", "le_inf");

    @Test
    void everyMetricTheDashboardQueriesIsOneTheApplicationRegisters() throws IOException {
        Set<String> referenced = metricNamesIn(DASHBOARD);

        // A dashboard that references nothing means the extraction broke, not that the dashboard
        // is clean. Without this the test would pass happily on an empty set for ever.
        assertThat(referenced)
                .as("the dashboard must query something - an empty set means this test stopped working")
                .isNotEmpty();

        assertThat(knownSeries()).containsAll(referenced);
    }

    @Test
    void everyMetricTheAlertRuleQueriesIsOneTheApplicationRegisters() throws IOException {
        Set<String> referenced = metricNamesIn(ALERTS);

        assertThat(referenced).isNotEmpty();
        assertThat(knownSeries()).containsAll(referenced);
    }

    @Test
    void theDashboardBindsToTheProvisionedDatasourceByUid() throws IOException {
        // The usual reason a provisioned dashboard comes up saying "Datasource not found": it
        // refers to the datasource by name, or by a UID Grafana generated on some other machine.
        String json = Files.readString(DASHBOARD);

        assertThat(json).contains("\"uid\": \"ecomdemo-prometheus\"");
        assertThat(json)
                .as("the datasource UID here must match the one provisioning creates")
                .contains(datasourceUid());
    }

    @Test
    void prometheusScrapesTheActuatorPath() throws IOException {
        // metrics_path is easy to leave at its default, and the default is /metrics — which on a
        // Spring Boot application is a 404. The target would simply be down for ever.
        String config = Files.readString(PROMETHEUS_CONFIG);

        assertThat(config).contains("metrics_path: /actuator/prometheus");
        assertThat(config)
                .as("the app must be reached by compose service name, not localhost")
                .contains("app:8080");
    }

    @Test
    void theAlertRuleGivesTheSituationTimeToBeReal() throws IOException {
        // `for:` is what separates an alert from a graph. Without it a single unlucky scrape
        // during a deployment pages somebody, and an alert that cries wolf is worse than none.
        String alerts = Files.readString(ALERTS);

        assertThat(alerts).containsPattern("for:\\s*\\d+[smh]");
        assertThat(alerts).contains("severity:");
        assertThat(alerts).contains("summary:");
    }

    /**
     * Every series name this application can produce, derived from a live registry rather than
     * hand-listed, plus the Spring Boot meters above.
     *
     * <p>{@link CheckoutMetrics} is constructed against a real {@link SimpleMeterRegistry}, so
     * the names come from the same constructor the application runs. Each meter is translated
     * into the suffixed spellings the Prometheus registry would emit — a counter gains
     * {@code _total}, a timer gains {@code _seconds_count} / {@code _sum} / {@code _bucket}, a
     * summary gains {@code _sum} / {@code _count} — because those, not the dotted Micrometer
     * names, are what the dashboard queries.
     */
    private static Set<String> knownSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CheckoutMetrics(registry);

        Set<String> names = new LinkedHashSet<>(SPRING_PROVIDED);
        for (Meter meter : registry.getMeters()) {
            String base = meter.getId().getName().replace('.', '_');
            switch (meter.getId().getType()) {
                case COUNTER -> names.add(base + "_total");
                case TIMER -> {
                    names.add(base + "_seconds_count");
                    names.add(base + "_seconds_sum");
                    names.add(base + "_seconds_max");
                    names.add(base + "_seconds_bucket");
                }
                case DISTRIBUTION_SUMMARY -> {
                    names.add(base + "_count");
                    names.add(base + "_sum");
                    names.add(base + "_max");
                    names.add(base + "_bucket");
                }
                default -> names.add(base);
            }
        }
        return names;
    }

    private static Set<String> metricNamesIn(Path file) throws IOException {
        Matcher matcher = METRIC_TOKEN.matcher(Files.readString(file));
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found.stream()
                .filter(name -> !NOT_METRICS.contains(name))
                // Only names this project could possibly own or has chosen to depend on. The
                // files are full of Grafana's own snake_case JSON keys, and checking those
                // against a meter registry would be meaningless.
                .filter(name -> knownSeries().contains(name) || looksLikeAMetricWeShouldOwn(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Whether an unrecognised token is one this test should insist on.
     *
     * <p>The prefixes are the metric families the dashboard is allowed to draw from. A token that
     * starts with one of them and is NOT a known series is exactly the rename this test hunts
     * for, so it is kept and fails the assertion. Everything else — {@code field_config},
     * {@code grid_pos} and the rest of Grafana's JSON — is dropped.
     */
    private static boolean looksLikeAMetricWeShouldOwn(String name) {
        return name.startsWith("orders_")
                || name.startsWith("order_value")
                || name.startsWith("checkout_")
                || name.startsWith("http_server_")
                || name.startsWith("jvm_")
                || name.startsWith("hikaricp_")
                || name.startsWith("process_cpu")
                || name.startsWith("system_cpu");
    }

    private static String datasourceUid() throws IOException {
        String provisioning =
                Files.readString(Path.of("docker/grafana/provisioning/datasources/prometheus.yml"));
        Matcher matcher = Pattern.compile("uid:\\s*(\\S+)").matcher(provisioning);
        assertThat(matcher.find()).as("the provisioned datasource must declare a uid").isTrue();
        return matcher.group(1);
    }
}

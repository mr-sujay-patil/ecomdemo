package com.ecomdemo.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KI-036: a cardinality budget, asserted.
 *
 * <p>Every distinct value of a meter tag is a time series of its own, in every service instance, kept for as long as
 * Prometheus keeps anything. The way a monitoring system is taken down by what it monitors is one innocent tag whose
 * values are not a closed set: an order id, a username, a product id, an error message. Spring Boot already bounds
 * the one it creates ({@code uri}: templates, and at most {@code max-uri-tags} of them, default 100), and
 * {@code ActuatorApiIT} proves the template. This test bounds the system's OWN meters.
 *
 * <p>The budget is the set of tag KEYS a meter of ours may carry, each with the closed set of values that makes it
 * safe. It reads every service's main sources, finds each statement that builds a meter ({@code Counter},
 * {@code Timer}, {@code Gauge}, {@code DistributionSummary}, {@code LongTaskTimer}), and collects the keys of its
 * {@code .tag(...)} calls, resolving constants; and the keys of any {@code lowCardinalityKeyValue}, which become
 * metric labels too. A key outside the budget fails the build: adding one means adding it here, with the reason its
 * values are bounded. A tracing span's tags ({@code span.tag(...)}) are not meters and are not counted: an id is the
 * right thing to put on a span.
 */
@DisplayName("the meters' tags stay within a cardinality budget (KI-036)")
class MeterTagBudgetTest {

    /** The budget: each allowed tag key, and why its values are a small closed set. */
    private static final Map<String, String> BUDGET = Map.of(
            "outcome", "a fixed list per meter: CheckoutOutcome's values, SagaReconciler.Outcome's, and string literals "
                    + "passed by each meter's own stop/record helper (answered, model_failed, ...)",
            "type", "the token counter's two kinds: prompt and completion",
            "key", "what the login throttle counted by: username or client (the KIND, never the username itself)");

    private static final List<String> SERVICES = List.of("common", "outbox", "ecomdemo-app", "catalog-service",
            "inventory-service", "customer-service", "notification-service", "payment-service", "assistant-service",
            "gateway-service");

    private static final Pattern METER_BUILDER =
            Pattern.compile("\\b(Counter|Timer|Gauge|DistributionSummary|LongTaskTimer|FunctionCounter)\\.builder\\(");
    private static final Pattern TAG = Pattern.compile("\\.tag\\(\\s*([A-Za-z_][\\w.]*|\"[^\"]*\")\\s*,");
    private static final Pattern LOW_CARDINALITY =
            Pattern.compile("lowCardinalityKeyValue\\(\\s*([A-Za-z_][\\w.]*|\"[^\"]*\")");
    private static final Pattern STRING_CONSTANT =
            Pattern.compile("static final String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"\\s*;");

    @Test
    @DisplayName("every tag key on a meter of ours is in the budget")
    void everyTagKeyIsBudgeted() {
        Map<String, Set<String>> used = tagKeysIn(sources());

        assertThat(used).as("the scan must find the meters it guards: an empty result means it stopped working")
                .containsKeys("outcome", "type", "key");
        assertThat(used.keySet())
                .as("tag keys outside the budget, with where they are used: %s", used)
                .isSubsetOf(BUDGET.keySet());
    }

    @Test
    @DisplayName("the scan catches a per-entity tag, through a constant too, and ignores a span's tags")
    void theScanCatchesWhatItIsFor() {
        Map<Path, String> sample = Map.of(Path.of("Sample.java"), """
                class Sample {
                    static final String TAG_ORDER = "order_id";
                    void record(long id) {
                        Counter.builder("orders.shipped").tag(TAG_ORDER, String.valueOf(id)).register(registry);
                        Timer.builder("lookups").tag("username", name).register(registry);
                        tracer.nextSpan().tag("order.id", String.valueOf(id)).start();
                        Observation.createNotStarted("x", registry).lowCardinalityKeyValue("product_id", "1");
                    }
                }
                """);

        assertThat(tagKeysIn(sample).keySet()).containsExactlyInAnyOrder("order_id", "username", "product_id");
    }

    @Test
    @DisplayName("Spring Boot's own bound on the uri tag is not raised")
    void theUriTagLimitIsNotRaised() {
        Pattern raised = Pattern.compile("max-uri-tags\\s*[=:]\\s*(\\d+)");
        for (String service : SERVICES) {
            Path resources = ProjectRoot.resolve(service + "/src/main/resources");
            if (!Files.isDirectory(resources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(resources)) {
                files.filter(f -> f.toString().matches(".*\\.(properties|ya?ml)$")).forEach(file -> {
                    Matcher m = raised.matcher(read(file));
                    while (m.find()) {
                        assertThat(Integer.parseInt(m.group(1))).as(file + " raises max-uri-tags").isLessThanOrEqualTo(100);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Tag key -> the files using it, for every meter-building statement and low-cardinality key value. */
    static Map<String, Set<String>> tagKeysIn(Map<Path, String> sources) {
        Map<String, String> constants = new HashMap<>();
        sources.values().forEach(source -> {
            Matcher m = STRING_CONSTANT.matcher(source);
            while (m.find()) {
                constants.put(m.group(1), m.group(2));
            }
        });
        Map<String, Set<String>> keys = new TreeMap<>();
        sources.forEach((file, source) -> {
            for (String statement : source.split(";")) {
                if (METER_BUILDER.matcher(statement).find()) {
                    collect(TAG.matcher(statement), constants, keys, file);
                }
                collect(LOW_CARDINALITY.matcher(statement), constants, keys, file);
            }
        });
        return keys;
    }

    private static void collect(Matcher m, Map<String, String> constants, Map<String, Set<String>> keys, Path file) {
        while (m.find()) {
            String key = m.group(1);
            if (key.startsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            } else {
                String name = key.substring(key.lastIndexOf('.') + 1);
                key = constants.getOrDefault(name, "<unresolved constant " + key + ">");
            }
            keys.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(file.getFileName().toString());
        }
    }

    private static Map<Path, String> sources() {
        Map<Path, String> sources = new HashMap<>();
        for (String service : SERVICES) {
            Path main = ProjectRoot.resolve(service + "/src/main/java");
            assertThat(main).as(service + "'s sources").isDirectory();
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(f -> f.toString().endsWith(".java")).forEach(f -> sources.put(f, read(f)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.ecomdemo.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.order.dto.OrderResponse;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The Actuator endpoints and the business meters, over real HTTP against real PostgreSQL.
 *
 * <p>The unit tests in {@code OrderServiceTest} already prove that a checkout moves a meter in a
 * {@code SimpleMeterRegistry}. What they cannot prove is the part between that registry and
 * Prometheus, which is where this phase's mistakes actually live: whether the meter survives
 * being rendered into the exposition format, whether the {@code application} common tag really
 * reaches every series, whether the security rules let an anonymous scraper in, and whether the
 * health groups exist at all — {@code probes.enabled} is one property, and without it
 * {@code /actuator/health/readiness} is a 404 that no unit test would notice.
 *
 * <p>Everything here is asserted against the SCRAPE TEXT rather than against the registry,
 * deliberately. Prometheus never sees a {@code Meter}; it sees these lines, and the name it reads
 * ({@code orders_placed_total}) is not the name the code wrote ({@code orders.placed}). A test
 * that asserts on the registry would keep passing through a change that renamed every series.
 */
@DisplayName("Actuator endpoints and the Prometheus scrape")
class ActuatorApiIT extends IntegrationTest {

    private final List<Long> createdProductIds = new ArrayList<>();

    private TestRestTemplate shopper;
    private TestRestTemplate admin;

    @BeforeEach
    void signIn() {
        shopper = asCustomer("it-actuator-shopper");
        admin = asAdmin();
    }

    @AfterEach
    void cleanUp() {
        createdProductIds.forEach(id -> admin.delete("/api/products/" + id));
        createdProductIds.clear();
    }

    // -------------------------------------------------------------------------------------
    // Health and the two probes
    // -------------------------------------------------------------------------------------

    @Test
    void healthIsAnonymous_becauseAProbeHasNoCredentials() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void healthHidesItsComponentsFromAnAnonymousCaller() {
        // show-details=when-authorized. The component names alone — "db", "redis" — describe the
        // infrastructure to anyone who asks, so the unauthenticated answer is the verdict only.
        ResponseEntity<Map> anonymous = rest.getForEntity("/actuator/health", Map.class);
        assertThat(anonymous.getBody()).doesNotContainKey("components");

        ResponseEntity<Map> authorized = admin.getForEntity("/actuator/health", Map.class);
        assertThat(authorized.getBody()).containsKey("components");
    }

    @Test
    void livenessAndReadinessExistAsSeparateGroups() {
        // Without management.health.probes.enabled both of these are 404 — and a 404 from a
        // Kubernetes liveness probe is a restart loop, so this is worth asserting rather than
        // assuming.
        assertThat(rest.getForEntity("/actuator/health/liveness", Map.class).getBody())
                .containsEntry("status", "UP");
        assertThat(rest.getForEntity("/actuator/health/readiness", Map.class).getBody())
                .containsEntry("status", "UP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessIncludesTheDatabaseButNotTheCache() {
        // The single most consequential line of configuration in this phase. Redis in readiness
        // would mean a cache outage takes every instance out of the load balancer, turning a
        // slowdown the application can absorb into an outage it cannot.
        Map<String, Object> body =
                admin.getForEntity("/actuator/health/readiness", Map.class).getBody();

        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).containsKey("db");
        assertThat(components).doesNotContainKey("redis");
    }

    @Test
    @SuppressWarnings("unchecked")
    void livenessDependsOnNothingButTheJvmItself() {
        // Restarting this process cannot fix somebody else's database, so nothing external
        // belongs in the probe whose remedy is a restart.
        Map<String, Object> body =
                admin.getForEntity("/actuator/health/liveness", Map.class).getBody();

        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).containsOnlyKeys("livenessState");
    }

    // -------------------------------------------------------------------------------------
    // Who may read what
    // -------------------------------------------------------------------------------------

    @Test
    void theScrapeEndpointIsAnonymous_becausePrometheusCarriesNoToken() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void theMetricsEndpointIsForOperatorsOnly() {
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(shopper.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void endpointsLeftOutOfTheExposureListDoNotExistOverHttp() {
        // Not an authorization rule — an allow-list. /actuator/env would print the whole
        // environment, JWT_SECRET included, and the reason it cannot is that it was never
        // published, not that a rule happens to guard it. Even the administrator gets a 404.
        assertThat(admin.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.getForEntity("/actuator/heapdump", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin.getForEntity("/actuator/loggers", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------------------
    // The business meters, as Prometheus sees them
    // -------------------------------------------------------------------------------------

    @Test
    void everyBusinessSeriesExistsBeforeAnyOrderIsPlaced() {
        // The pre-registration in CheckoutMetrics, verified where it matters: in the scrape.
        // An absent series is not zero — PromQL over it returns no rows, so a panel reads
        // "No data" and an alert on it can never fire.
        String scrape = scrape();

        assertThat(scrape).contains("orders_placed_total");
        assertThat(scrape).contains("order_value_sum");
        assertThat(scrape).contains("order_value_count");
        for (CheckoutOutcome outcome : CheckoutOutcome.values()) {
            assertThat(scrape)
                    .as("checkout.duration must carry the %s tag before it ever happens", outcome)
                    .contains("outcome=\"" + outcome.tagValue() + "\"");
        }
    }

    @Test
    void placingAnOrderMovesTheThreeBusinessMeters() {
        long productId = createProduct("IT metrics widget", new BigDecimal("12.50"), 10);
        String before = scrape();
        double ordersBefore = series(before, "orders_placed_total", Map.of());
        double revenueBefore = series(before, "order_value_sum", Map.of());
        double placedBefore =
                series(before, "checkout_duration_seconds_count", Map.of("outcome", "placed"));

        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(productId, 2), Object.class);
        ResponseEntity<OrderResponse> order =
                shopper.postForEntity("/api/orders", null, OrderResponse.class);
        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String after = scrape();
        assertThat(series(after, "orders_placed_total", Map.of()) - ordersBefore).isEqualTo(1.0);
        // 2 x 12.50. The dashboard's revenue panel is this number and nothing else, so it has to
        // be the order's own total rather than a rounded or averaged version of it.
        assertThat(series(after, "order_value_sum", Map.of()) - revenueBefore).isEqualTo(25.0);
        assertThat(series(after, "checkout_duration_seconds_count", Map.of("outcome", "placed"))
                        - placedBefore)
                .isEqualTo(1.0);
    }

    @Test
    void aRejectedCheckoutIsTimedWithItsOwnOutcome_andCountsNoOrder() {
        // The cart is empty, so this checkout is refused on its merits. It must land on the
        // empty_cart timer and leave `conflict` — the series the alert rule watches — alone.
        clearCart();
        String before = scrape();
        double emptyBefore =
                series(before, "checkout_duration_seconds_count", Map.of("outcome", "empty_cart"));
        double conflictBefore =
                series(before, "checkout_duration_seconds_count", Map.of("outcome", "conflict"));
        double ordersBefore = series(before, "orders_placed_total", Map.of());

        ResponseEntity<String> refused = shopper.postForEntity("/api/orders", null, String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String after = scrape();
        assertThat(series(after, "checkout_duration_seconds_count", Map.of("outcome", "empty_cart"))
                        - emptyBefore)
                .isEqualTo(1.0);
        assertThat(series(after, "checkout_duration_seconds_count", Map.of("outcome", "conflict")))
                .isEqualTo(conflictBefore);
        assertThat(series(after, "orders_placed_total", Map.of())).isEqualTo(ordersBefore);
    }

    @Test
    void theCheckoutTimerExportsHistogramBuckets() {
        // percentiles-histogram, not percentiles: the buckets are what Prometheus'
        // histogram_quantile() needs, and they are the only form that can be aggregated across
        // instances. Without them the latency panel draws nothing.
        long productId = createProduct("IT histogram widget", new BigDecimal("5.00"), 5);
        shopper.postForEntity("/api/cart/items", new AddCartItemRequest(productId, 1), Object.class);
        shopper.postForEntity("/api/orders", null, OrderResponse.class);

        assertThat(scrape())
                .contains("checkout_duration_seconds_bucket")
                // One of the SLO boundaries configured in application.properties.
                .contains("le=\"1.0\"");
    }

    @Test
    void everyMeterCarriesTheApplicationCommonTag() {
        // Applied once in MetricsConfig rather than at each call site, which is what makes it
        // true of the auto-configured JVM meters as well as of ours.
        String scrape = scrape();

        assertThat(scrape).contains("orders_placed_total{application=\"ecomdemo\"}");
        assertThat(scrape)
                .as("the common tag must reach auto-configured meters too")
                .containsPattern("jvm_memory_used_bytes\\{[^}]*application=\"ecomdemo\"");
    }

    @Test
    void actuatorsOwnEndpointsAreNotCountedAsApplicationTraffic() {
        // Prometheus scrapes every 15s and the container probes every 10s. Without the meter
        // filter, the busiest endpoint in the shop is the one that reports how busy the shop is.
        rest.getForEntity("/actuator/health", String.class);
        rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(scrape()).doesNotContain("uri=\"/actuator");
    }

    @Test
    void httpRequestsAreTimedByUriTemplate_notByRealPath() {
        long productId = createProduct("IT template widget", new BigDecimal("3.00"), 3);

        shopper.getForEntity("/api/products/" + productId, ProductResponse.class);

        // The id is NOT in the series. Were it interpolated, every product ever fetched would be
        // its own time series — the textbook way to take a monitoring system down with the
        // cardinality of the thing it is monitoring.
        String scrape = scrape();
        assertThat(scrape).contains("uri=\"/api/products/{id}\"");
        assertThat(scrape).doesNotContain("uri=\"/api/products/" + productId + "\"");
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private String scrape() {
        return rest.getForObject("/actuator/prometheus", String.class);
    }

    /**
     * The value of one series in the exposition text, summed over everything that matches.
     *
     * <p>Parsed rather than grepped: a substring search for {@code orders_placed_total} also
     * matches {@code orders_placed_total_created}, and matching a label by substring depends on
     * the order Micrometer happened to emit the labels in. Missing series read as 0.0, which is
     * the right default here — every test using this asserts on a DIFFERENCE, and a series that
     * genuinely must exist is asserted for separately above.
     */
    private static double series(String scrape, String name, Map<String, String> labels) {
        double total = 0.0;
        for (String raw : scrape.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int brace = line.indexOf('{');
            int space = line.lastIndexOf(' ');
            if (space < 0) {
                continue;
            }
            String metricName = brace >= 0 ? line.substring(0, brace) : line.substring(0, space);
            if (!metricName.equals(name)) {
                continue;
            }
            String labelText = brace >= 0 ? line.substring(brace, space) : "";
            boolean matches = labels.entrySet().stream()
                    .allMatch(e -> labelText.contains(e.getKey() + "=\"" + e.getValue() + "\""));
            if (matches) {
                total += Double.parseDouble(line.substring(space + 1));
            }
        }
        return total;
    }

    private long createProduct(String name, BigDecimal price, int stock) {
        ProductResponse created = admin.postForEntity(
                        "/api/products",
                        new ProductRequest(name, "created by ActuatorApiIT", price, stock, "TEST"),
                        ProductResponse.class)
                .getBody();
        createdProductIds.add(created.id());
        return created.id();
    }

    private void clearCart() {
        CartResponse cart = shopper.getForObject("/api/cart", CartResponse.class);
        cart.items().forEach(item -> shopper.delete("/api/cart/items/" + item.productId()));
    }
}

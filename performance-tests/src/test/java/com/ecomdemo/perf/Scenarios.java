package com.ecomdemo.perf;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAs;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.repeat;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The user journeys, shared by the three simulations.
 *
 * <p>Request NAMES are what the report groups by, so they are stable and path-shaped
 * ({@code GET /api/products/{id}}), never the concrete URL: one line per endpoint, not one per
 * product id.
 *
 * <p>Semantic search ({@code /api/products/search}) is deliberately NOT in the browse journey.
 * Each search embeds the query with the configured model (Ollama on this machine, on the GPU), so
 * loading it measures the model server, not this system. It is its own experiment if ever wanted.
 */
final class Scenarios {

    private static final Duration THINK_MIN = Duration.ofMillis(200);
    private static final Duration THINK_MAX = Duration.ofMillis(800);
    private static final Duration POLL_EVERY = Duration.ofMillis(250);
    /** 120 x 250 ms = 30 s: longer than any healthy saga, short enough to end a stuck session. */
    private static final int MAX_POLLS = 120;

    private final TestData.Prepared data = TestData.prepare();

    HttpProtocolBuilder protocol() {
        return http.baseUrl(PerfConfig.BASE_URL)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .userAgentHeader("ecomdemo-gatling");
    }

    /**
     * Each session takes the next account in turn and uses its token for every request. Circular:
     * 200 accounts serve any number of sessions, and consecutive sessions get different accounts,
     * which spreads the per-user rate limit and keeps two checkouts off one cart.
     */
    private Iterator<Map<String, Object>> customers() {
        List<String> tokens = data.customerTokens();
        AtomicInteger next = new AtomicInteger();
        return Stream.generate((Supplier<Map<String, Object>>) () -> Map.of(
                        "token", tokens.get(Math.floorMod(next.getAndIncrement(), tokens.size()))))
                .iterator();
    }

    private long randomProduct() {
        List<Long> ids = data.productIds();
        return ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
    }

    private ChainBuilder think() {
        return pause(THINK_MIN, THINK_MAX);
    }

    /** List the catalogue, then look at three products. */
    ChainBuilder browseChain() {
        return exec(http("GET /api/products")
                        .get("/api/products")
                        .header("Authorization", "Bearer #{token}")
                        .check(status().is(200)))
                .exec(think())
                .exec(repeat(3).on(
                        exec(session -> session.set("productId", randomProduct()))
                                .exec(http("GET /api/products/{id}")
                                        .get("/api/products/#{productId}")
                                        .header("Authorization", "Bearer #{token}")
                                        .check(status().is(200)))
                                .exec(think())));
    }

    /**
     * Put one or two products in the cart, check out, then poll until the saga has settled.
     *
     * <p>The checkout is only half the story: {@code POST /api/orders} answers 201 as soon as the
     * order is saved PENDING, and stock and payment happen afterwards over Kafka. The group
     * "order settled" therefore covers the whole journey including the polling pauses, and its
     * DURATION in the report is the time a shopper waits to see CONFIRMED - the number that
     * shows a slow saga, which the 201's latency never would.
     */
    ChainBuilder checkoutChain() {
        return group("order settled").on(
                exec(session -> session.set("lines", 1 + ThreadLocalRandom.current().nextInt(2)))
                        .exec(repeat("#{lines}").on(
                                exec(session -> session.set("productId", randomProduct()))
                                        .exec(http("POST /api/cart/items")
                                                .post("/api/cart/items")
                                                .header("Authorization", "Bearer #{token}")
                                                .body(StringBody("{\"productId\":#{productId},\"quantity\":1}"))
                                                .check(status().is(200)))))
                        .exec(http("POST /api/orders")
                                .post("/api/orders")
                                .header("Authorization", "Bearer #{token}")
                                .check(status().is(201))
                                .check(jsonPath("$.id").saveAs("orderId")))
                        .exitHereIfFailed()
                        .exec(session -> session.set("orderStatus", "PENDING")
                                .set("placedAt", System.nanoTime()))
                        .exec(asLongAs(session -> "PENDING".equals(session.getString("orderStatus"))
                                        && session.getInt("polls") < MAX_POLLS, "polls")
                                .on(pause(POLL_EVERY)
                                        .exec(http("GET /api/orders/{id}/status")
                                                .get("/api/orders/#{orderId}/status")
                                                .header("Authorization", "Bearer #{token}")
                                                .check(status().is(200))
                                                .check(jsonPath("$.status").saveAs("orderStatus")))))
                        // A CANCELLED order, or one still PENDING after 30 s, marks the GROUP as KO.
                        // Every request in it may have been a 200, so the request error count
                        // would never show it; checkoutAssertion() checks the group instead.
                        .exec(session -> {
                            if ("CONFIRMED".equals(session.getString("orderStatus"))) {
                                SettleTimes.confirmed(
                                        (System.nanoTime() - session.getLong("placedAt")) / 1_000_000);
                                return session;
                            }
                            SettleTimes.notConfirmed();
                            return session.markAsFailed();
                        }));
    }

    ScenarioBuilder browse() {
        return scenario("browse").exec(feed(customers())).exec(browseChain());
    }

    ScenarioBuilder checkout() {
        return scenario("checkout").exec(feed(customers())).exec(checkoutChain());
    }

    /** Checks that FAIL a run: errors and a p95 far beyond anything acceptable. */
    static List<Assertion> assertions() {
        return List.of(
                global().failedRequests().percent().lte(PerfConfig.MAX_FAILED_PERCENT),
                global().responseTime().percentile(95.0).lte(PerfConfig.MAX_P95_MILLIS));
    }

    /** Orders that did not end CONFIRMED (cancelled, or stuck PENDING) fail the run too. */
    static Assertion checkoutAssertion() {
        return details("order settled").failedRequests().percent().lte(PerfConfig.MAX_FAILED_PERCENT);
    }
}

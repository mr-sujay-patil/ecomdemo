package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.RedisContainerConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The catalogue read route's timeout and circuit breaker (KI-004), against a catalog-service that is
 * deliberately slow, then healthy.
 *
 * <p>Before the fix the gateway waited as long as catalog-service did: a hung one held the shopper's
 * connection for ever, and a dead one cost every request its own connect attempt. Now a read gives up
 * at the route's 2 s limit, and after enough failures the breaker opens and refuses at once, without
 * calling catalog-service at all. The order of the tests matters (one breaker, shared) and is pinned.
 *
 * <p><strong>Not a {@code GatewayTest}, on purpose.</strong> That base class points every route at a
 * closed port, and a closed port also ends in the fallback (fast, 503), so a test built on it would
 * pass whether or not the TIMEOUT works. This one needs a real, slow upstream, so it sets up its own
 * context (same Redis for the rate limiter) and points only the catalogue at the stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RedisContainerConfig.class)
@DisplayName("Catalogue read route: timeout and circuit breaker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogRouteResilienceIT {

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<Duration> DELAY = new AtomicReference<>(Duration.ofSeconds(4));

    private static HttpServer catalog;

    @BeforeAll
    static void startCatalog() throws IOException {
        if (catalog != null) {
            return;
        }
        catalog = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        catalog.setExecutor(Executors.newCachedThreadPool());
        catalog.createContext("/", exchange -> {
            CALLS.incrementAndGet();
            sleep(DELAY.get());
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        catalog.start();
    }

    @AfterAll
    static void stopCatalog() {
        catalog.stop(0);
    }

    @DynamicPropertySource
    static void pointCatalogAtTheStub(DynamicPropertyRegistry properties) throws IOException {
        // The server must exist before the context does, and @BeforeAll runs after property sources.
        if (catalog == null) {
            startCatalog();
        }
        properties.add("CATALOG_BASE_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    }

    @LocalServerPort
    private int port;

    private WebTestClient web;

    @BeforeEach
    void bindTheClientToTheRunningServer() {
        web = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("a slow read gives up at the route's limit and answers 503 with Retry-After, not after 4 s")
    void aSlowReadIsCutOff() {
        long started = System.nanoTime();

        web.get().uri("/api/products")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.message").value(m -> assertThat((String) m).contains("temporarily unavailable"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(3500));
    }

    @Test
    @Order(2)
    @DisplayName("after repeated failures the breaker opens: a read is refused at once, catalog-service is not called")
    void theBreakerOpens() {
        for (int i = 0; i < 6; i++) {
            web.get().uri("/api/products").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        int callsBefore = CALLS.get();
        long started = System.nanoTime();

        web.get().uri("/api/products")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);

        assertThat(CALLS.get()).as("an open breaker makes no call upstream").isEqualTo(callsBefore);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    @Order(3)
    @DisplayName("and closes again once catalog-service answers: the breaker is a pause, not a ban")
    void theBreakerRecovers() throws InterruptedException {
        DELAY.set(Duration.ZERO);
        Thread.sleep(Duration.ofSeconds(11)); // the breaker's 10 s open wait

        for (int i = 0; i < 4; i++) {
            web.get().uri("/api/products").exchange().expectStatus().isOk();
        }
    }

    @Test
    @Order(4)
    @DisplayName("search is not on the fast route: a 3 s answer is waited for, not cut at 2 s")
    void searchHasItsOwnLongerLimit() {
        DELAY.set(Duration.ofSeconds(3));

        web.get().uri("/api/products/search?q=laptop").exchange().expectStatus().isOk();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

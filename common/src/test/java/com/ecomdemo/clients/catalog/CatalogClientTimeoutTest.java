package com.ecomdemo.clients.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecomdemo.jwt.ServiceTokenProvider;
import com.ecomdemo.jwt.ServiceTokens;
import com.ecomdemo.support.TestJwt;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The catalog client's timeouts, against a real HTTP server that is deliberately slow.
 *
 * <p>A mock cannot test a timeout: a timeout is a property of the socket, and a mocked
 * {@code RestClient} has none. So this starts the JDK's own {@link HttpServer} and makes it take
 * longer than the client is willing to wait. What is being proved is the WIRING — that the
 * numbers in {@link CatalogProperties} reach the request factory the client really uses, and that
 * the batch upsert really goes through the client with the longer limit.
 */
@DisplayName("catalog client timeouts")
class CatalogClientTimeoutTest {

    private static final Duration SLOW = Duration.ofMillis(1500);

    private static HttpServer slowCatalog;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CatalogClientConfig.class)
            .withBean(CatalogClient.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(ServiceTokenProvider.class, CatalogClientTimeoutTest::tokens)
            .withPropertyValues(
                    "ecomdemo.catalog.base-url=http://localhost:" + slowCatalog.getAddress().getPort(),
                    "ecomdemo.catalog.read-timeout=300ms",
                    "ecomdemo.catalog.bulk-read-timeout=5s");

    @BeforeAll
    static void startSlowCatalog() throws IOException {
        slowCatalog = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // Every endpoint answers correctly - just late. The server has a thread per request so
        // an abandoned slow request does not delay the next test's.
        slowCatalog.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slowCatalog.createContext("/api/products", exchange -> {
            sleep(SLOW);
            String body = exchange.getRequestURI().getPath().endsWith("/batch")
                    ? "[]"
                    : "{\"id\":1,\"name\":\"Slow\",\"price\":1.00}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        slowCatalog.start();
    }

    @AfterAll
    static void stopSlowCatalog() {
        slowCatalog.stop(0);
    }

    @Test
    @DisplayName("a product read gives up at the read timeout instead of waiting for the answer")
    void aSingleReadTimesOut() {
        context.run(ctx -> {
            CatalogGateway catalogue = ctx.getBean(CatalogGateway.class);
            long started = System.nanoTime();

            assertThatThrownBy(() -> catalogue.requireProduct(1L))
                    .isInstanceOf(ResourceAccessException.class);

            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            // Well under the server's 1.5 s: it was the client that stopped waiting.
            assertThat(waited).isLessThan(Duration.ofMillis(1200));
        });
    }

    @Test
    @DisplayName("the batch upsert uses the longer bulk timeout, so a slow import still completes")
    void theBulkUpsertWaitsLonger() {
        context.run(ctx -> {
            CatalogGateway catalogue = ctx.getBean(CatalogGateway.class);

            List<ProductSnapshot> result = catalogue.upsertAll(List.of());

            assertThat(result).isEmpty();
        });
    }

    private static ServiceTokenProvider tokens() {
        // The timeouts under test are the catalogue client's; the token only has to be present.
        return () -> TestJwt.service("ecomdemo-app", ServiceTokens.CATALOG_READ);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

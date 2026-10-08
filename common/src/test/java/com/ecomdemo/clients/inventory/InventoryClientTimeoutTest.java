package com.ecomdemo.clients.inventory;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The inventory client's timeouts, against a real HTTP server that is deliberately slow (KI-004).
 *
 * <p>A mock cannot test a timeout: it is a property of the socket. So this starts the JDK's own
 * {@link HttpServer} and makes it take longer than the client is willing to wait. What is proved is
 * the WIRING: that the number in {@link InventoryProperties} reaches the request factory the client
 * really uses. Before KI-004 this client had no timeout at all, so both calls below simply waited
 * for the full 1.5 s (and a really hung server would have held them for ever).
 */
@DisplayName("inventory client timeouts")
class InventoryClientTimeoutTest {

    private static final Duration SLOW = Duration.ofMillis(1500);

    private static HttpServer slowInventory;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(InventoryClientConfig.class)
            .withBean(InventoryClient.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(ServiceTokenProvider.class, InventoryClientTimeoutTest::tokens)
            .withPropertyValues(
                    "ecomdemo.inventory.base-url=http://localhost:" + slowInventory.getAddress().getPort(),
                    "ecomdemo.inventory.read-timeout=300ms");

    @BeforeAll
    static void startSlowInventory() throws IOException {
        slowInventory = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // A thread per request, so an abandoned slow request does not delay the next test's.
        slowInventory.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slowInventory.createContext("/api/inventory", exchange -> {
            sleep(SLOW);
            byte[] bytes = "{\"productId\":1,\"quantity\":5}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        slowInventory.start();
    }

    @AfterAll
    static void stopSlowInventory() {
        slowInventory.stop(0);
    }

    @Test
    @DisplayName("a stock read gives up at the read timeout instead of waiting for the answer")
    void aReadTimesOut() {
        context.run(ctx -> {
            InventoryGateway inventory = ctx.getBean(InventoryGateway.class);
            long started = System.nanoTime();

            assertThatThrownBy(() -> inventory.quantityFor(1L)).isInstanceOf(ResourceAccessException.class);

            // Well under the server's 1.5 s: it was the client that stopped waiting.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1200));
        });
    }

    @Test
    @DisplayName("a write times out too: a stock update cannot hold a request thread for ever")
    void aWriteTimesOut() {
        context.run(ctx -> {
            InventoryGateway inventory = ctx.getBean(InventoryGateway.class);
            long started = System.nanoTime();

            assertThatThrownBy(() -> inventory.setStockLevel(1L, 5)).isInstanceOf(ResourceAccessException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1200));
        });
    }

    @Test
    @DisplayName("the defaults are 250 ms to connect and 500 ms to read")
    void theDefaults() {
        InventoryProperties defaults = new InventoryProperties(null, null, null);

        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    private static ServiceTokenProvider tokens() {
        // The timeouts under test are the client's; the token only has to be present.
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

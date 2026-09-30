package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.gateway.support.GatewayTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway is the one place a browser can reach, so it is where the API is documented (KI-001).
 *
 * <p>Each service still builds its own OpenAPI document - only it knows its controllers. The gateway
 * routes {@code /v3/api-docs/{service}} to that service's {@code /v3/api-docs} and serves one Swagger
 * UI whose dropdown lists them. Before KI-001 all of this answered 401 or 404.
 *
 * <p>The upstreams here are JDK {@link HttpServer}s, one per service, each answering with its own name
 * and recording the path it was asked for. That proves both halves of a route: that it reaches the
 * RIGHT service, and that the path is rewritten to the one the service actually serves.
 */
@DisplayName("API documentation at the edge")
class ApiDocsIT extends GatewayTest {

    /** Gateway document name → the environment variable its route's uri reads. */
    private static final Map<String, String> DOCUMENTED = new LinkedHashMap<>();

    static {
        DOCUMENTED.put("catalog", "CATALOG_BASE_URL");
        DOCUMENTED.put("customer", "CUSTOMER_BASE_URL");
        DOCUMENTED.put("inventory", "INVENTORY_BASE_URL");
        DOCUMENTED.put("assistant", "ASSISTANT_BASE_URL");
        DOCUMENTED.put("app", "APP_BASE_URL");
    }

    private static final Map<String, HttpServer> UPSTREAMS = new ConcurrentHashMap<>();
    private static final Map<String, String> PATH_SEEN = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void pointEachDocumentedRouteAtItsOwnFakeService(DynamicPropertyRegistry properties) {
        DOCUMENTED.forEach((service, variable) -> {
            HttpServer upstream = UPSTREAMS.computeIfAbsent(service, ApiDocsIT::start);
            properties.add(variable, () -> "http://localhost:" + upstream.getAddress().getPort());
        });
    }

    private static HttpServer start(String service) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                PATH_SEEN.put(service, exchange.getRequestURI().getPath());
                byte[] body = """
                        {"openapi":"3.1.0","info":{"title":"%s","version":"v1"},"paths":{}}"""
                        .formatted(service).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopTheUpstreams() {
        UPSTREAMS.values().forEach(server -> server.stop(0));
    }

    @ParameterizedTest(name = "/v3/api-docs/{0}")
    @ValueSource(strings = {"catalog", "customer", "inventory", "assistant", "app"})
    @DisplayName("each documented service's spec is served anonymously, from that service's /v3/api-docs")
    void eachServiceSpecIsRoutedToThatService(String service) {
        web.get().uri("/v3/api-docs/" + service)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isEqualTo("3.1.0")
                .jsonPath("$.info.title").isEqualTo(service);

        assertThat(PATH_SEEN.get(service)).as("the path %s received", service).isEqualTo("/v3/api-docs");
    }

    @ParameterizedTest(name = "/v3/api-docs/{0}")
    @ValueSource(strings = {"payment", "notification", "gateway", "unknown"})
    @DisplayName("services with no public API expose no docs through the gateway")
    void undocumentedServicesAreNotFound(String service) {
        web.get().uri("/v3/api-docs/" + service)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Swagger UI is served anonymously at the URL the README gives")
    void swaggerUiIsServed() {
        URI location = web.get().uri("/swagger-ui.html")
                .exchange()
                .expectStatus().is3xxRedirection()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();
        assertThat(location).as("/swagger-ui.html redirects to the UI").isNotNull();

        web.get().uri(location.getPath())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
    }

    @Test
    @DisplayName("the UI's dropdown lists every documented service, each at its gateway URL")
    void theDropdownListsEveryService() {
        var config = web.get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertThat(config).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> urls = (List<Map<String, String>>) config.get("urls");
        assertThat(urls).extracting(entry -> entry.get("url"))
                .containsExactlyInAnyOrder(DOCUMENTED.keySet().stream()
                        .map(service -> "/v3/api-docs/" + service)
                        .toArray(String[]::new));
    }
}

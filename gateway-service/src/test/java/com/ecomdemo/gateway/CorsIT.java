package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.RedisContainerConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * A browser on another origin must be able to use the API (KI-041).
 *
 * <p>Before any cross-origin request that carries a token or a JSON body, a browser sends a
 * PREFLIGHT: an {@code OPTIONS} request with {@code Origin}, {@code Access-Control-Request-Method}
 * and {@code Access-Control-Request-Headers}, and never a token. Until KI-041 the gateway's security
 * chain answered it 401 before the gateway's CORS configuration ever saw it, so a browser app could
 * not even log in. Curl, the smoke test and Gatling send no preflight, which is why nothing noticed.
 *
 * <p>Standalone rather than a {@code GatewayTest}: a real routed response is needed to prove that
 * a request gets exactly ONE {@code Access-Control-Allow-Origin} (a duplicate makes the browser
 * reject the response), and {@code GatewayTest} points every upstream at a closed port. Every
 * upstream here is one JDK {@link HttpServer} that answers 200 and adds no CORS headers itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RedisContainerConfig.class)
@DisplayName("CORS at the edge")
class CorsIT {

    /** The default of `CORS_ALLOWED_ORIGINS` in application.yml: a frontend's dev server. */
    private static final String ALLOWED = "http://localhost:3000";

    private static final HttpServer UPSTREAM = start();

    @DynamicPropertySource
    static void pointEveryRouteAtTheFakeUpstream(DynamicPropertyRegistry properties) {
        String upstream = "http://localhost:" + UPSTREAM.getAddress().getPort();
        for (String variable : List.of("CATALOG_BASE_URL", "CUSTOMER_BASE_URL", "INVENTORY_BASE_URL",
                "APP_BASE_URL", "ASSISTANT_BASE_URL")) {
            properties.add(variable, () -> upstream);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
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
    static void stopTheUpstream() {
        UPSTREAM.stop(0);
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

    private WebTestClient.ResponseSpec preflight(String origin, String path, HttpMethod method, String headers) {
        return web.options().uri(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers)
                .exchange();
    }

    @Test
    @DisplayName("the login preflight (a JSON POST) is allowed, before any token exists")
    void theLoginPreflightIsAllowed() {
        HttpHeaders headers = preflight(ALLOWED, "/api/auth/login", HttpMethod.POST, "content-type")
                .expectStatus().isOk()
                .returnResult(Void.class)
                .getResponseHeaders();

        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(headers.getAccessControlAllowMethods()).contains(HttpMethod.POST);
        assertThat(headers.getAccessControlAllowHeaders())
                .anySatisfy(header -> assertThat(header).isEqualToIgnoringCase("content-type"));
    }

    @Test
    @DisplayName("the preflight of an authenticated call is allowed, and names Authorization")
    void anAuthenticatedCallsPreflightIsAllowed() {
        HttpHeaders headers = preflight(ALLOWED, "/api/cart", HttpMethod.GET, "authorization")
                .expectStatus().isOk()
                .returnResult(Void.class)
                .getResponseHeaders();

        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(headers.getAccessControlAllowHeaders())
                .anySatisfy(header -> assertThat(header).isEqualToIgnoringCase("authorization"));
    }

    @Test
    @DisplayName("a preflight from an origin that is not allowed is refused, with no allow headers")
    void aForeignOriginIsRefused() {
        HttpHeaders headers = preflight("http://evil.example", "/api/auth/login", HttpMethod.POST, "content-type")
                .expectStatus().isForbidden()
                .returnResult(Void.class)
                .getResponseHeaders();

        assertThat(headers.getAccessControlAllowOrigin()).isNull();
    }

    @Test
    @DisplayName("a routed cross-origin response carries exactly one Access-Control-Allow-Origin")
    void aRoutedResponseHasOneAllowOrigin() {
        HttpHeaders headers = web.get().uri("/api/products")
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Void.class)
                .getResponseHeaders();

        assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(ALLOWED);
    }

    /** KI-007: X-Total-Count and Link are not CORS-safelisted, so a page script reads them only if exposed. */
    @Test
    @DisplayName("a cross-origin response exposes the paging headers, or the browser hides them from the page")
    void thePagingHeadersAreExposed() {
        HttpHeaders headers = web.get().uri("/api/products")
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .exchange()
                .expectStatus().isOk()
                .returnResult(Void.class)
                .getResponseHeaders();

        assertThat(headers.getAccessControlExposeHeaders())
                .contains("X-Total-Count", "Link", "X-Correlation-Id");
    }

    @Test
    @DisplayName("a refused cross-origin call still carries the allow header, so the browser can read the 401")
    void aRefusalIsReadableByTheBrowser() {
        HttpHeaders headers = web.get().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .exchange()
                .expectStatus().isUnauthorized()
                .returnResult(Void.class)
                .getResponseHeaders();

        assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(ALLOWED);
    }
}

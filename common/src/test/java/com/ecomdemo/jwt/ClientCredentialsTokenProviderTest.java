package com.ecomdemo.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * How a service gets its token from customer-service, and how often it asks (Phase 33).
 *
 * <p>Replaces {@code ServiceTokenProviderTest}, which tested a provider that SIGNED its own tokens
 * with the shared HS256 secret. That provider is gone: nothing but customer-service signs now. The
 * properties worth keeping (one token per lifetime, renewed before it expires) are asserted again
 * here, against the real client credentials request.
 *
 * <p>customer-service is a JDK {@link HttpServer} that records each request and answers like the
 * real {@code /oauth2/token}: a new token each time, valid for 900 seconds.
 */
@DisplayName("Service tokens from customer-service (client credentials)")
class ClientCredentialsTokenProviderTest {

    private HttpServer server;
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final AtomicInteger issued = new AtomicInteger();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-30T10:00:00Z"));

    @BeforeEach
    void startTheTokenEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"access_token":"token-%d","token_type":"Bearer","expires_in":900,"scope":"inventory:read"}"""
                    .formatted(issued.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopIt() {
        server.stop(0);
    }

    private ClientCredentialsTokenProvider provider(String secret) {
        String uri = "http://localhost:" + server.getAddress().getPort() + "/oauth2/token";
        return new ClientCredentialsTokenProvider(RestClient.create(), uri, "catalog-service", secret, clock);
    }

    @Test
    @DisplayName("it asks with the client credentials grant, authenticating as itself with HTTP Basic")
    void asksWithItsOwnCredentials() {
        String token = provider("s3cret").token();

        assertThat(token).isEqualTo("token-1");
        assertThat(bodies).containsExactly("grant_type=client_credentials");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("catalog-service:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(authorizations).containsExactly(expected);
    }

    @Test
    @DisplayName("one request per token lifetime, not one per outbound call")
    void cachesTheToken() {
        ClientCredentialsTokenProvider provider = provider("s3cret");

        assertThat(provider.token()).isEqualTo(provider.token()).isEqualTo(provider.token());
        assertThat(issued).hasValue(1);
    }

    @Test
    @DisplayName("a new token is fetched a minute before the old one expires, not after")
    void renewsBeforeExpiry() {
        ClientCredentialsTokenProvider provider = provider("s3cret");
        provider.token();

        clock.advanceSeconds(900 - 61);
        assertThat(provider.token()).as("still more than a minute left").isEqualTo("token-1");

        clock.advanceSeconds(2);
        assertThat(provider.token()).as("inside the last minute").isEqualTo("token-2");
    }

    @Test
    @DisplayName("without a client secret it says which variable to set, at the first call")
    void explainsAMissingSecret() {
        assertThatThrownBy(() -> provider("").token())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog-service")
                .hasMessageContaining("SERVICE_CLIENT_SECRET");
        assertThat(issued).as("nothing was sent without credentials").hasValue(0);
    }

    /** A clock a test can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

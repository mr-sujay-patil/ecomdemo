package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.RedisAcl;
import com.ecomdemo.support.TestJwt;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * The rate limiter still limits when the gateway is the Redis user {@code gateway} (Phase 37), not {@code default}.
 *
 * <p><strong>Why this needs its own test.</strong> {@code RedisRateLimiter} FAILS OPEN: if Redis refuses its
 * script, the request is let through as if no limit existed. So an ACL that forgot {@code +evalsha}, {@code +select}
 * or a command the Lua script calls would not break a single request; it would quietly turn the rate limit off.
 * {@link RateLimitIT} cannot see that (its Redis has no ACL). Here Redis runs with the cluster's own ACL file, the
 * gateway logs in as {@code gateway} and keeps its real configuration (database 1, the script, the key resolver),
 * and the test asserts both sides: a burst is refused with 429, and Redis's {@code ACL LOG} recorded no refusal.
 *
 * <p>Not a {@code GatewayTest}: that base class wires the shared Redis without an ACL by service connection,
 * which would win over the user and password set here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("The rate limiter under the gateway's Redis ACL user (Phase 37)")
class RateLimitAclIT {

    private static final int BURST = 400;

    private static GenericContainer<?> redis;

    @LocalServerPort
    private int port;

    private WebTestClient web;

    @DynamicPropertySource
    static void redisAsTheGatewayUser(DynamicPropertyRegistry properties) throws IOException {
        redis = RedisAcl.start();
        properties.add("spring.data.redis.host", redis::getHost);
        properties.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        properties.add("spring.data.redis.username", () -> "gateway");
        properties.add("spring.data.redis.password", () -> RedisAcl.TEST_PASSWORD);
        // As GatewayTest does: every route to a port nothing listens on; the limiter answers before the proxy call.
        String nowhere = "http://localhost:" + closedPort();
        for (String upstream : new String[] {
                "CATALOG_BASE_URL", "CUSTOMER_BASE_URL", "INVENTORY_BASE_URL", "APP_BASE_URL", "ASSISTANT_BASE_URL"}) {
            properties.add(upstream, () -> nowhere);
        }
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void client() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    @Test
    @DisplayName("a burst is refused with 429, the buckets are in database 1, and Redis refused nothing")
    void theLimiterWorksUnderItsUser() throws Exception {
        RedisAcl.forgetRefusals(redis);
        String token = TestJwt.user("acl-burst-tester", 4243, "CUSTOMER");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < BURST; i++) {
            statuses.add(web.get().uri("/api/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectBody().returnResult().getStatus().value());
        }

        assertThat(statuses).as("statuses seen: %s", statuses.stream().distinct().sorted().toList())
                .contains(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(RedisAcl.cli(redis, RedisAcl.ADMIN, "-n", "1", "EXISTS",
                "request_rate_limiter.{user:acl-burst-tester}.tokens")).isEqualTo("1");
        assertThat(RedisAcl.refusals(redis)).as("Redis's ACL LOG").isEmpty();
    }

    @Test
    @DisplayName("the gateway's user is refused the catalogue cache, the assistant's keys and FLUSHALL")
    void theGatewayUserIsRefusedEverythingElse() throws Exception {
        assertThat(RedisAcl.cli(redis, "gateway", "GET", "product::1")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "gateway", "GET", "assistant:memory:x")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "gateway", "FLUSHALL")).startsWith("NOPERM");
        assertThat(RedisAcl.cli(redis, "gateway", "-n", "1", "GET", "request_rate_limiter.{x}.tokens"))
                .doesNotContain("NOPERM");
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

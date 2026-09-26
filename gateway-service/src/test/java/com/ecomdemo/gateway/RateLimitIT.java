package com.ecomdemo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.gateway.support.GatewayTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * That the rate limit is real, against a real Redis — and that it limits one caller rather than everyone.
 *
 * <p><strong>Why a container and not a mock.</strong> {@code RedisRateLimiter} is a Lua script executed
 * inside Redis: the token bucket's arithmetic, and its atomicity across concurrent requests, live there
 * and not in Java. A stubbed store would prove the filter is wired into the chain and nothing whatever
 * about whether a burst is refused, which is the only claim anybody cares about.
 *
 * <p><strong>Every burst here is made as an AUTHENTICATED user, and the first version was not.</strong>
 * Bursting anonymously exhausted the bucket keyed by IP — and then {@code EdgeSecurityIT}, sharing the
 * context and therefore the same Redis and the same IP, started getting 429s for requests that had
 * nothing to do with rate limiting. A test that breaks another test by succeeding is worth more than a
 * quick fix: it is the per-caller key doing exactly what it was designed for, observed from the wrong
 * side. So these tests take their own identity, and the last one asserts the isolation directly.
 */
@DisplayName("The rate limiter")
class RateLimitIT extends GatewayTest {

    /**
     * Comfortably more than the burst capacity of 100, so the bucket cannot refill fast enough to
     * satisfy all of them however quick the machine is.
     */
    private static final int BURST = 400;

    private List<Integer> burstAs(String username) {
        String token = tokenWithRoles(username, "CUSTOMER");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < BURST; i++) {
            statuses.add(web.get().uri("/api/products")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectBody()
                    .returnResult()
                    .getStatus()
                    .value());
        }
        return statuses;
    }

    /**
     * The assertion is deliberately "some request is refused" rather than "the 101st request is
     * refused". Redis refills the bucket on a clock that keeps running while the test makes its calls,
     * so the index of the first rejection depends on how fast this machine is. Pinning it would turn a
     * correctness test into a performance assertion — the mistake Phase 20d had to undo in three
     * separate smoke checks.
     */
    @Test
    @DisplayName("a burst past the burst capacity is refused with 429")
    void aBurstIsRefused() {
        List<Integer> statuses = burstAs("burst-tester");

        assertThat(statuses)
                .as("a burst of %d requests must hit the limit; statuses seen: %s",
                        BURST, statuses.stream().distinct().sorted().toList())
                .contains(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    /**
     * And that the limit is a limit rather than a wall.
     *
     * <p>A limiter that refused everything after the first burst until a restart would pass the test
     * above and be useless. This waits for the bucket to refill and asserts the caller is served again
     * — the difference between throttling and banning.
     */
    @Test
    @DisplayName("and the limit lifts once the bucket refills, rather than banning the caller")
    void theLimitLiftsAgain() {
        String token = tokenWithRoles("recovering-tester", "CUSTOMER");
        burstAs("recovering-tester");

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(statusFor(token))
                        .as("the caller is served again once tokens are back")
                        .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
    }

    /**
     * THE POINT OF KEYING PER CALLER, asserted rather than assumed.
     *
     * <p>A limiter with one shared bucket is a denial-of-service tool: the first busy client exhausts it
     * and everybody else is refused. This is the test that would fail if the key resolver were ever
     * replaced by a constant — which is easy to do by accident, because a constant key passes every
     * other test in this class.
     */
    @Test
    @DisplayName("one caller's burst does not refuse a DIFFERENT caller")
    void oneCallersBurstDoesNotAffectAnother() {
        burstAs("noisy-neighbour");

        String quiet = tokenWithRoles("quiet-caller", "CUSTOMER");
        assertThat(statusFor(quiet))
                .as("a caller who has made one request is not refused because somebody else made 400")
                .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    private int statusFor(String token) {
        return web.get().uri("/api/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectBody()
                .returnResult()
                .getStatus()
                .value();
    }
}

package com.ecomdemo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.auth.dto.LoginRequest;
import com.ecomdemo.support.CustomerIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Repeated wrong passwords are throttled, against PostgreSQL, over a real socket (Phase 33).
 *
 * <p>Each test logs in "from" its own client address through {@code X-Forwarded-For} (the last hop
 * is what counts, as the gateway appends it), so the per-client counter of one test never blocks
 * another. The defaults apply: 5 failures per username, 20 per client, a first block of 30 seconds.
 */
@DisplayName("Login throttling")
class LoginThrottleIT extends CustomerIntegrationTest {

    private static String address() {
        // TEST-NET-3 (RFC 5737): an address reserved for documentation, so it is nobody's.
        return "203.0.113." + (1 + Math.floorMod(UUID.randomUUID().hashCode(), 250));
    }

    /** The body as text: a success is a token, a failure an ApiError, and one type fits both. */
    private ResponseEntity<String> attempt(String username, String password, String client) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", client);
        return rest.postForEntity("/api/auth/login",
                new HttpEntity<>(new LoginRequest(username, password), headers), String.class);
    }

    @Test
    @DisplayName("the fifth wrong password blocks the username: 429 with Retry-After, even for the right password")
    void blocksAUsernameAfterFiveFailures() {
        String username = "it-throttle-" + UUID.randomUUID().toString().substring(0, 8);
        asCustomer(username);
        String client = address();

        for (int i = 1; i <= 4; i++) {
            assertThat(attempt(username, "wrong-password", client).getStatusCode())
                    .as("failure %d is an ordinary 401", i).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The fifth failure is counted and starts the block; it still answers 401.
        assertThat(attempt(username, "wrong-password", client).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> blocked = attempt(username, IT_PASSWORD, client);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        long retryAfter = Long.parseLong(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 30L);
        assertThat(blocked.getBody()).contains("Try again in " + retryAfter + " seconds");

        // Blocked by USERNAME, so another address is refused too: that is what stops guessing one
        // person's password from many machines.
        assertThat(attempt(username, IT_PASSWORD, address()).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a correct password before the limit clears the username's failures")
    void aSuccessResetsTheUsername() {
        String username = "it-throttle-" + UUID.randomUUID().toString().substring(0, 8);
        asCustomer(username);
        String client = address();

        for (int i = 0; i < 4; i++) {
            attempt(username, "wrong-password", client);
        }
        assertThat(attempt(username, IT_PASSWORD, client).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Four more failures after the reset still do not reach the limit of five.
        for (int i = 0; i < 4; i++) {
            assertThat(attempt(username, "wrong-password", client).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(attempt(username, IT_PASSWORD, client).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("twenty failures from one address block that address, whichever usernames it tries")
    void blocksAClientTryingManyUsernames() {
        String client = address();
        for (int i = 0; i < 20; i++) {
            // Credential stuffing: a different (non-existent) username each time, so no username
            // counter ever reaches its limit. Only the per-client counter can stop this.
            assertThat(attempt("stuffed-" + i + "-" + UUID.randomUUID(), "leaked-password", client).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(attempt("stuffed-next", "leaked-password", client).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Another address is unaffected.
        assertThat(attempt("stuffed-next", "leaked-password", address()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

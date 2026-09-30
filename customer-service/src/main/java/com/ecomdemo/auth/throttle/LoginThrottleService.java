package com.ecomdemo.auth.throttle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slows password guessing down to where it stops paying (Phase 33).
 *
 * <p><strong>Two counters, two attacks.</strong> Failures are counted per USERNAME (one account's
 * password guessed over and over) and per CLIENT address (one machine trying many accounts, which is
 * credential stuffing with a leaked password list). Either one reaching its limit blocks further
 * attempts with that username, or from that address, for a while.
 *
 * <p><strong>Throttling, not lockout.</strong> A block always ends by itself, and doubles only while
 * the failures continue. A permanent lockout would be a denial of service anyone could run against
 * any account, by typing its name with a wrong password five times.
 *
 * <p><strong>Checked before the password.</strong> A blocked attempt is refused without running
 * BCrypt, so a flood of guesses costs the attacker time and costs this service almost nothing.
 */
@Service
@EnableConfigurationProperties(LoginThrottleProperties.class)
public class LoginThrottleService {

    private final LoginThrottleRepository repository;
    private final LoginThrottleProperties policy;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter failures;
    private final Counter throttledByUsername;
    private final Counter throttledByClient;

    LoginThrottleService(LoginThrottleRepository repository, LoginThrottleProperties policy,
            TransactionTemplate transactions, MeterRegistry meters, Optional<Clock> clock) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.clock = clock.orElseGet(Clock::systemUTC);
        this.failures = Counter.builder("ecomdemo.auth.login.failures")
                .description("Failed logins (wrong username or password)")
                .register(meters);
        this.throttledByUsername = Counter.builder("ecomdemo.auth.login.throttled")
                .description("Login attempts refused because of earlier failures")
                .tag("key", "username").register(meters);
        this.throttledByClient = Counter.builder("ecomdemo.auth.login.throttled")
                .description("Login attempts refused because of earlier failures")
                .tag("key", "client").register(meters);
    }

    /** Refuses the attempt, before the password is checked, if either key is blocked. */
    public void checkAllowed(String username, String clientAddress) {
        Instant now = clock.instant();
        Duration wait = blockedFor(userKey(username), now);
        if (!wait.isZero()) {
            throttledByUsername.increment();
            throw new LoginThrottledException(wait);
        }
        wait = blockedFor(clientKey(clientAddress), now);
        if (!wait.isZero()) {
            throttledByClient.increment();
            throw new LoginThrottledException(wait);
        }
    }

    /** Counts a wrong username or password against both keys. */
    public void recordFailure(String username, String clientAddress) {
        failures.increment();
        fail(userKey(username), policy.usernameLimit());
        fail(clientKey(clientAddress), policy.clientLimit());
    }

    /** A correct password clears the username's failures. The address keeps its count. */
    public void recordSuccess(String username) {
        transactions.executeWithoutResult(status -> repository.deleteById(userKey(username)));
    }

    private Duration blockedFor(String key, Instant now) {
        return repository.findById(key)
                .filter(throttle -> throttle.blockedAt(now))
                .map(throttle -> Duration.between(now, throttle.blockedUntil()))
                .orElse(Duration.ZERO);
    }

    private void fail(String key, int limit) {
        try {
            failOnce(key, limit);
        } catch (DataIntegrityViolationException firstFailureRace) {
            // Two first failures for the same key raced to INSERT; the loser counts again against
            // the row the winner created.
            failOnce(key, limit);
        }
    }

    private void failOnce(String key, int limit) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            LoginThrottle throttle = repository.findForUpdate(key).orElseGet(() -> new LoginThrottle(key, now));
            throttle.resetIfStale(now, policy.window());
            throttle.fail(now, limit, policy.baseBlock(), policy.maxBlock());
            repository.saveAndFlush(throttle);
        });
    }

    /** Case-insensitive, so "Asha" and "asha" are one target, and bounded to the column width. */
    static String userKey(String username) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return truncate("user:" + name);
    }

    static String clientKey(String clientAddress) {
        return truncate("client:" + (clientAddress == null ? "unknown" : clientAddress));
    }

    private static String truncate(String key) {
        return key.length() <= 120 ? key : key.substring(0, 120);
    }
}

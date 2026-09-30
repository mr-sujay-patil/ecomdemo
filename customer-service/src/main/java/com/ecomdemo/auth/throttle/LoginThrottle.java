package com.ecomdemo.auth.throttle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The failures counted against one username or one client address (table {@code login_throttle}). */
@Entity
@Table(name = "login_throttle")
public class LoginThrottle {

    @Id
    @Column(name = "throttle_key", length = 120)
    private String key;

    @Column(nullable = false)
    private int failures;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    protected LoginThrottle() {
    }

    LoginThrottle(String key, Instant now) {
        this.key = key;
        this.windowStartedAt = now;
    }

    String key() {
        return key;
    }

    int failures() {
        return failures;
    }

    Instant blockedUntil() {
        return blockedUntil;
    }

    boolean blockedAt(Instant now) {
        return blockedUntil != null && now.isBefore(blockedUntil);
    }

    /** A new window starts once the old one is over and no block is running. */
    void resetIfStale(Instant now, java.time.Duration window) {
        if (!blockedAt(now) && now.isAfter(windowStartedAt.plus(window))) {
            failures = 0;
            windowStartedAt = now;
            blockedUntil = null;
        }
    }

    void fail(Instant now, int limit, java.time.Duration baseBlock, java.time.Duration maxBlock) {
        failures++;
        if (failures >= limit) {
            // Exponential backoff: the limit-th failure blocks for baseBlock, each further one doubles
            // it, up to maxBlock. Doubling is capped at 2^20 before multiplying, so it cannot overflow.
            long factor = 1L << Math.min(failures - limit, 20);
            java.time.Duration block = baseBlock.multipliedBy(factor);
            blockedUntil = now.plus(block.compareTo(maxBlock) > 0 ? maxBlock : block);
        }
    }
}

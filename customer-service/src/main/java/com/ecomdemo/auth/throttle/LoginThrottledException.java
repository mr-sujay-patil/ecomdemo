package com.ecomdemo.auth.throttle;

import java.time.Duration;

/** A login refused because of earlier failures; becomes 429 with {@code Retry-After}. */
public class LoginThrottledException extends RuntimeException {

    private final Duration retryAfter;

    public LoginThrottledException(Duration retryAfter) {
        super("Too many failed logins");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded UP: a client that waits exactly this long is allowed again. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds();
        return retryAfter.toNanosPart() > 0 || seconds == 0 ? seconds + 1 : seconds;
    }
}

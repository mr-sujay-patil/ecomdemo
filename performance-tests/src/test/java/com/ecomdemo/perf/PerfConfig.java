package com.ecomdemo.perf;

import java.time.Duration;

/**
 * Every knob of a run, read from the ENVIRONMENT.
 *
 * <p>Environment variables rather than {@code -D} system properties because the Gatling Maven
 * plugin runs the simulation in a forked JVM: the environment is inherited by that JVM, a
 * {@code -D} given to Maven is not. One mechanism that always works beats two where one silently
 * does nothing.
 *
 * <p>The defaults describe a SHORT run that any laptop can take: the point of a default is that
 * {@code scripts/perf-test.sh browse steady} works first time, not that it finds a limit.
 */
final class PerfConfig {

    /** The gateway - the one door real clients use, so the one the load goes through. */
    static final String BASE_URL = env("PERF_BASE_URL", "http://localhost:8080");

    /** {@code ramp}, {@code steady} or {@code spike}; see {@link LoadProfile}. */
    static final String PROFILE = env("PERF_PROFILE", "steady");

    /** New sessions per second at the plateau (open model: arrivals, not concurrent users). */
    static final double RATE = Double.parseDouble(env("PERF_RATE", "20"));

    /** How long the plateau (steady) or the climb (ramp) lasts. */
    static final Duration DURATION = Duration.ofSeconds(Long.parseLong(env("PERF_DURATION_SECONDS", "60")));

    /** Spike: how many sessions arrive within the 10 s burst. */
    static final int SPIKE_USERS = Integer.parseInt(env("PERF_SPIKE_USERS", "300"));

    /**
     * Customer accounts shared by the sessions. The gateway rate-limits PER USER (50 requests a
     * second, burst 100), so the pool size is also what keeps a test from measuring the rate
     * limiter: 200 users x 50/s is far above anything this host can serve.
     *
     * <p>It also has to be large enough that two concurrent checkout sessions rarely share an
     * account - they would share a cart. Rule of thumb: users >= rate x session length.
     */
    static final int USERS = Integer.parseInt(env("PERF_USERS", "200"));

    /** Products created (once) for the load, each with a stock level no run can exhaust. */
    static final int PRODUCTS = Integer.parseInt(env("PERF_PRODUCTS", "20"));

    static final String ADMIN_USER = env("PERF_ADMIN_USER", "admin");
    // Throwaway LOCAL development credentials, the same defaults the smoke test uses.
    static final String ADMIN_PASSWORD = env("PERF_ADMIN_PASSWORD", "admin123");
    static final String CUSTOMER_PASSWORD = env("PERF_CUSTOMER_PASSWORD", "perf-pass-123");

    // Assertion thresholds. Generous on purpose: they catch a broken run (errors, a hang), while the
    // interesting numbers are read from the report and compared across runs in docs/performance.md.
    static final double MAX_FAILED_PERCENT = Double.parseDouble(env("PERF_MAX_FAILED_PERCENT", "1"));
    static final int MAX_P95_MILLIS = Integer.parseInt(env("PERF_MAX_P95_MS", "2000"));

    private PerfConfig() {}

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

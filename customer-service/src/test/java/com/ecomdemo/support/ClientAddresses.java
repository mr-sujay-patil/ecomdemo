package com.ecomdemo.support;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client addresses for tests that log in "from" their own address through {@code X-Forwarded-For}.
 *
 * <p>Each call returns a different address (up to 250 per run), so one test's blocked client can
 * never be another's. Drawing at random did not guarantee that: two calls collided one run in 250,
 * and the "another address is unaffected" check then got a 429 (KI-046). The first address is
 * random, so a database that outlives a run does not hand a new run a client still blocked.
 */
public final class ClientAddresses {

    private static final int HOSTS = 250;
    private static final AtomicInteger NEXT = new AtomicInteger(ThreadLocalRandom.current().nextInt(HOSTS));

    private ClientAddresses() {
    }

    /** TEST-NET-3 (RFC 5737): an address reserved for documentation, so it is nobody's. */
    public static String next() {
        return "203.0.113." + (1 + Math.floorMod(NEXT.getAndIncrement(), HOSTS));
    }
}

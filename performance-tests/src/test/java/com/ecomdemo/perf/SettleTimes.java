package com.ecomdemo.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Time from {@code POST /api/orders} answering 201 to the status poll that first saw CONFIRMED.
 *
 * <p>Gatling cannot report this itself. Its group statistics are CUMULATED response time - the
 * sum of the requests inside the group - which leaves out the pauses between polls, and those
 * pauses are exactly the time the saga is working. So each session records its own figure here
 * and the simulation prints the percentiles when it ends (the line {@code scripts/perf-test.sh}
 * picks up).
 *
 * <p>Resolution is the poll interval (250 ms): an order that settled 10 ms after a poll is seen
 * one poll later. Fine for comparing runs, which is what this is for.
 */
final class SettleTimes {

    private static final ConcurrentLinkedQueue<Long> CONFIRMED_MILLIS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger NOT_CONFIRMED = new AtomicInteger();

    private SettleTimes() {}

    static void confirmed(long millis) {
        CONFIRMED_MILLIS.add(millis);
    }

    static void notConfirmed() {
        NOT_CONFIRMED.incrementAndGet();
    }

    static String summary() {
        List<Long> sorted = new ArrayList<>(CONFIRMED_MILLIS);
        if (sorted.isEmpty()) {
            return "[perf] order settled: no confirmed orders (not confirmed: %d)"
                    .formatted(NOT_CONFIRMED.get());
        }
        Collections.sort(sorted);
        return "[perf] order settled: n=%d p50=%d p95=%d p99=%d max=%d ms (not confirmed: %d)".formatted(
                sorted.size(), pct(sorted, 50), pct(sorted, 95), pct(sorted, 99),
                sorted.getLast(), NOT_CONFIRMED.get());
    }

    /** Nearest-rank percentile. */
    private static long pct(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}

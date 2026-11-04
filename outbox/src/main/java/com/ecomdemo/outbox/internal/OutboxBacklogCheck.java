package com.ecomdemo.outbox.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Measures the outbox's backlog for {@link OutboxMetrics} (KI-031).
 *
 * <p>Its own schedule, every 15 s (Prometheus's scrape interval), not the relay's one-second tick:
 * an idle relay stays one query per tick, and a backlog does not need measuring more often than it
 * is scraped. Like the relay it runs in every instance; each reads the same table.
 *
 * <p>A check that fails (the database unreachable) keeps the last values: an outage must not read
 * as an empty outbox, and the age gauge goes on growing from the last oldest event it knows.
 */
@Component
class OutboxBacklogCheck {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogCheck.class);

    private final OutboxEventRepository outbox;
    private final OutboxMetrics metrics;

    OutboxBacklogCheck(OutboxEventRepository outbox, OutboxMetrics metrics) {
        this.outbox = outbox;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${ecomdemo.outbox.backlog-check-delay:15s}",
            initialDelayString = "${ecomdemo.outbox.backlog-check-delay:15s}")
    void check() {
        try {
            metrics.backlog(outbox.backlog());
        } catch (RuntimeException e) {
            log.warn("Outbox backlog check failed; the gauges keep their last values: {}", e.getMessage());
        }
    }
}

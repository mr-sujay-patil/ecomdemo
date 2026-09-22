package com.ecomdemo.messaging.internal;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sweeps published events out of the outbox.
 *
 * <p><strong>Why an outbox needs a bin at all.</strong> Every order writes a row here for ever.
 * The relay's query is "the oldest pending rows", which is fast only because the pending set is
 * tiny and indexed; but the TABLE keeps growing, its index with it, and the backups and the
 * vacuum and the disk all grow too. A year of orders is a table whose only live content is
 * whatever the last second produced. The outbox is a queue that happens to be stored in a
 * database, and a queue nobody empties is a log.
 *
 * <p><strong>Only published rows, and only old ones.</strong> Both halves of that are enforced in
 * {@link OutboxEventRepository#deletePublishedBefore(Instant)}, and the first is the dangerous
 * one: a sweep written as "older than the cutoff" would delete pending events precisely because
 * an outage had made them old — deleting the exact rows the whole phase exists to protect, at the
 * exact moment they mattered. A pending row is never old enough.
 *
 * <p><strong>The retention window is for humans, not for correctness.</strong> Nothing re-reads a
 * published row; the system does not need it a millisecond after the broker acknowledges it. Seven
 * days is there so that "what did we publish for order 4812 on Friday?" is a query rather than an
 * apology. That is why the number can be an opinion instead of a proof.
 *
 * <p>Like {@code SalesReportScheduler}, this is a timer inside this JVM, so two instances sweep
 * twice. Unlike the report, that is harmless: the second delete finds the rows already gone.
 */
@Component
class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxEventRepository outbox;
    private final OutboxProperties properties;

    OutboxCleanupJob(OutboxEventRepository outbox, OutboxProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    /**
     * A cron rather than a fixed delay, because this is housekeeping and housekeeping belongs at
     * a time somebody chose. {@code "-"} disables it outright, which is how a second instance, or
     * a developer's laptop, opts out.
     */
    @Scheduled(cron = "${ecomdemo.outbox.cleanup-cron}")
    @Transactional
    void sweep() {
        Instant cutoff = Instant.now().minus(properties.retention());
        int deleted = outbox.deletePublishedBefore(cutoff);

        // Logged even when it deletes nothing. "The cleanup ran and there was nothing to do" and
        // "the cleanup never ran" look identical in a silent log, and they are very different
        // explanations for a table that will not stop growing.
        log.info(
                "Outbox cleanup removed {} published event(s) older than {} ({} retention)",
                deleted,
                cutoff,
                properties.retention());
    }
}

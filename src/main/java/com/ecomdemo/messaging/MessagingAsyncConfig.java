package com.ecomdemo.messaging;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The thread pool that publishes events, so that checkout never waits for a broker.
 *
 * <p><strong>Why this exists at all — it was not in the first draft of this phase.</strong> The
 * failure test found it: with Kafka stopped, a checkout returned 201 after <b>97 seconds</b>. The
 * reasoning that produced that bug is worth writing down, because it sounds right.
 * {@code KafkaTemplate.send} returns a future, so it looks asynchronous — but it is only
 * asynchronous once the producer knows which broker leads the partition. Before that it blocks
 * <em>inside</em> {@code send}, waiting for cluster metadata, for up to {@code max.block.ms}
 * (default sixty seconds). And the {@code AFTER_COMMIT} listener runs on the thread that
 * committed the transaction — the request thread. So a broker outage became checkout latency:
 * precisely the coupling the asynchronous design was supposed to remove.
 *
 * <p>Two changes fix it, and both are needed. {@code max.block.ms} is lowered to five seconds in
 * {@code application.properties} so nothing waits a minute for a broker that is not there; and the
 * publication moves onto this pool, so the five seconds are spent on a thread nobody is waiting
 * for. The customer's checkout is unaffected either way.
 *
 * <p><strong>What this does not fix.</strong> The event is still lost if the broker is down —
 * verified: the order was committed, no notification was ever written, and the log carries an
 * ERROR naming the order. Moving the send to another thread changes who waits, not whether the
 * message survives. Only writing the event to the same database in the same transaction does
 * that, which is the transactional outbox and is Phase 18.
 */
@Configuration
@EnableAsync
public class MessagingAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(MessagingAsyncConfig.class);

    /** Named so that a thread dump, and every log line from this pool, says what it is. */
    public static final String PUBLISHER_EXECUTOR = "eventPublisherExecutor";

    @Bean(PUBLISHER_EXECUTOR)
    Executor eventPublisherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("event-publisher-");

        // Small and BOUNDED, both of which are deliberate. Publishing is IO that spends its time
        // waiting, so a handful of threads is plenty; and an unbounded queue is how a broker
        // outage turns into an OutOfMemoryError, silently accumulating one task per order until
        // the heap is gone.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);

        // ABORT, not CallerRuns. CallerRunsPolicy is the usual advice, and it is exactly wrong
        // here: it hands the work back to the thread that submitted it — the request thread —
        // which is the blocking this whole class exists to prevent. Aborting drops the event and
        // says so, which is bad, but it is the same badness as the broker being down, and it is
        // visible.
        executor.setRejectedExecutionHandler(
                (runnable, pool) ->
                        log.error(
                                "Event publication rejected: the publisher queue is full ({} queued, "
                                        + "{} active). An event has been DROPPED — the order is committed "
                                        + "but nothing was published for it.",
                                pool.getQueue().size(),
                                pool.getActiveCount()));

        // Let the JVM finish what is queued on shutdown rather than dropping it on the floor.
        // Bounded, because "wait for the queue to drain" against a broker that is down would hang
        // the shutdown instead.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}

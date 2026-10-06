package com.ecomdemo.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.messaging.OutboxWriter;
import com.ecomdemo.outbox.internal.OutboxEvent;
import com.ecomdemo.outbox.internal.OutboxEventRepository;
import com.ecomdemo.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Only one outbox relay publishes at a time, however many instances run (KI-002).
 *
 * <p>The application's own relay runs here, against PostgreSQL and a real broker. This test plays
 * the OTHER instance's relay: a thread that takes the relay lock and holds it inside a transaction,
 * as a relay does while it sends a batch. While it holds the lock the real relay must leave the
 * pending row alone, and once it lets go the relay must publish it.
 */
@DisplayName("Outbox relay lock")
class OutboxRelayLockIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** Several poll cycles (the default delay is one second): enough for a relay that ignores the lock. */
    private static final Duration LONG_ENOUGH_TO_HAVE_HAPPENED = Duration.ofSeconds(4);

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private TransactionTemplate transactions;

    private boolean isPublished(String orderId) {
        return outbox.findAll().stream()
                .filter(event -> event.getAggregateId().equals(orderId))
                .allMatch(OutboxEvent::isPublished);
    }

    @Test
    @DisplayName("a relay yields while another instance's relay holds the lock, and publishes when it lets go")
    void yieldsToTheRelayThatHoldsTheLock() throws Exception {
        long orderId = 800_000_000L + (long) (Math.random() * 99_999_999L);
        CountDownLatch otherRelayHasTheLock = new CountDownLatch(1);
        CountDownLatch otherRelayIsDone = new CountDownLatch(1);
        ExecutorService otherInstance = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> held = otherInstance.submit(() -> transactions.execute(status -> {
                // The real relay also takes the lock for an instant on every tick, so this may have
                // to wait for its turn.
                // On THIS thread: Awaitility would poll on one of its own, outside this transaction,
                // and a lock taken there is gone the moment it is taken.
                await().atMost(TIMEOUT).pollInSameThread()
                        .until(() -> outbox.tryLockRelay(OutboxEventRepository.RELAY_LOCK_KEY));
                otherRelayHasTheLock.countDown();
                try {
                    return otherRelayIsDone.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }));
            assertThat(otherRelayHasTheLock.await(30, TimeUnit.SECONDS)).isTrue();

            OrderPlacedEvent event =
                    OrderPlacedEvent.of(orderId, "it-outbox-lock", new BigDecimal("1.00"), 1, Instant.now());
            transactions.executeWithoutResult(status -> outboxWriter.append(event));

            await().during(LONG_ENOUGH_TO_HAVE_HAPPENED).atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(isPublished(String.valueOf(orderId)))
                            .as("the relay must not publish while another relay holds the lock")
                            .isFalse());

            otherRelayIsDone.countDown();
            assertThat(held.get(30, TimeUnit.SECONDS)).isTrue();

            await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(isPublished(String.valueOf(orderId)))
                            .as("once the lock is free the relay publishes the row")
                            .isTrue());
        } finally {
            otherRelayIsDone.countDown();
            otherInstance.shutdownNow();
        }
    }
}

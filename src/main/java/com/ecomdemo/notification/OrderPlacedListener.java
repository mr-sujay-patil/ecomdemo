package com.ecomdemo.notification;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.messaging.OrderPlacedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code orders.placed} and writes a notification.
 *
 * <p><strong>Retries happen on TOPICS, not in this thread.</strong> That is the part worth
 * understanding. A consumer that catches an exception and sleeps before retrying holds its
 * partition the whole time — and because Kafka hands out partitions, not messages, everything
 * behind that record waits too. One poison message with a thirty-second backoff stops the whole
 * partition for thirty seconds, which is called head-of-line blocking and is how a single bad
 * order delays every order after it.
 *
 * <p>{@code @RetryableTopic} avoids that by forwarding the failed record to
 * {@code orders.placed-retry-0}, committing the original offset, and moving on. A separate
 * listener consumes the retry topic later; the main partition never waits. The cost, stated
 * honestly, is that a retried record is no longer in order relative to the ones after it — which
 * is acceptable here because a notification does not depend on any other order, and would NOT be
 * acceptable for, say, a sequence of state changes to the same aggregate.
 *
 * <p>After the last attempt the record goes to {@code orders.placed-dlt} and stays there. Nothing
 * consumes a DLT: it is a queue of messages that need a person, and consuming it automatically
 * would turn "this cannot be processed" into an infinite loop with extra steps.
 */
@Component
public class OrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

    private final NotificationService notificationService;

    public OrderPlacedListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Three attempts in total, one second apart, doubling.
     *
     * <p>The numbers are small so the behaviour is observable in a test and a smoke run; a real
     * system would use longer ones. The shape is what matters: an exponential backoff gives a
     * dependency that is briefly overloaded time to recover instead of hammering it at a fixed
     * rate, and a fixed retry count means a message cannot circle for ever. The jitter spreads
     * the retries of a batch that all failed at once — without it, a thousand records that failed
     * together retry together, and the dependency that fell over is hit by the same thundering
     * herd one second later.
     *
     * <p>Note the annotation: {@code org.springframework.kafka.annotation.BackOff}, not Spring
     * Retry's {@code @Backoff}. Spring Kafka 4 brought its own, which is what carries
     * {@code jitter}; the older one still compiles in other projects and is the wrong import
     * here — the attribute on {@code @RetryableTopic} is {@code backOff}, with a capital O.
     *
     * <p>{@code SameIntervalTopicReuseStrategy.SINGLE_TOPIC} keeps every attempt that shares a
     * delay on ONE retry topic rather than creating a topic per attempt. With three attempts the
     * difference is small; with ten it is the difference between two topics and ten.
     *
     * <p><strong>{@code SUFFIX_WITH_INDEX_VALUE} is not cosmetic, and the default bites.</strong>
     * Spring names retry topics after the DELAY by default — {@code orders.placed-retry-1000},
     * {@code -retry-2000} — which is readable right up until the delay stops being a constant.
     * With jitter it is a different number every time the application starts, so the first run
     * created {@code orders.placed-retry-1031} and {@code -retry-1661}, and the next would have
     * created two more, for ever, each holding a handful of orphaned messages that nothing
     * consumes. Naming them by INDEX gives {@code orders.placed-retry-0} and {@code -retry-1},
     * stable across restarts and independent of the backoff.
     *
     * <p><strong>No {@code exclude}/{@code include} list here on purpose.</strong> Every failure
     * this listener can produce is worth retrying — a database that is briefly unavailable, a
     * lock timeout — and the one class of failure that is NOT worth retrying, a message that
     * cannot be deserialised, never reaches this method at all: it fails in the deserializer and
     * is routed by the error handler, which is why the poison-message test sends malformed bytes
     * rather than a valid event that happens to break.
     *
     * <p><strong>{@code kafkaTemplate} is named explicitly, and Phase 18 is why.</strong> The
     * retry and dead-letter publications go out through a {@code KafkaTemplate} that Spring Kafka
     * resolves BY TYPE. Phase 17 had exactly one, so the default worked and the attribute was not
     * needed. Phase 18 added a second — the outbox relay's {@code <String, String>} template,
     * which sends the committed payload bytes through a {@code StringSerializer} — and the
     * resolution picked that one. The retry publication then tried to send an
     * {@code OrderPlacedEvent} through a serializer that accepts only strings and threw
     * {@code ClassCastException} inside {@code DeadLetterPublishingRecoverer}.
     *
     * <p>The consequence was worse than a failed send: the recoverer could not move the record
     * anywhere, so the poison message was never recovered, the offset was never committed, and
     * the whole partition stopped — which is precisely the head-of-line blocking the retry topics
     * exist to prevent, reintroduced by a bean added three packages away. The smoke test caught
     * it; nothing in the unit or integration suites would have, because neither has two templates
     * and a poison message at the same time.
     *
     * <p>Naming the template makes the choice explicit rather than emergent, so adding a third
     * template later cannot silently re-point this.
     */
    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 1000, multiplier = 2.0, jitter = 250),
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            retryTopicSuffix = KafkaTopics.RETRY_SUFFIX,
            dltTopicSuffix = KafkaTopics.DLT_SUFFIX,
            kafkaTemplate = "kafkaTemplate")
    @KafkaListener(
            topics = KafkaTopics.ORDERS_PLACED,
            groupId = KafkaTopics.NOTIFICATION_GROUP)
    public void onOrderPlaced(
            OrderPlacedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info(
                "Received OrderPlacedEvent {} for order {} from {}-{} at offset {}",
                event.eventId(),
                event.orderId(),
                topic,
                partition,
                offset);

        notificationService.handle(event);
    }

    /**
     * The end of the line.
     *
     * <p>This logs at ERROR because it is the one thing in the pipeline that genuinely needs a
     * human: the message is on the DLT, no notification was written, and nothing else will happen
     * to it. The record is logged with its topic and offset so it can be found and, once the
     * cause is fixed, replayed — which is a deliberate act by an operator, not something the
     * application should do to itself.
     *
     * <p>Note the parameter type: a raw {@code ConsumerRecord} with byte values rather than an
     * {@code OrderPlacedEvent}. A message on the DLT may well be one that could not be
     * deserialised in the first place, so a handler that asked for the typed object could not
     * handle exactly the case it exists for.
     */
    @DltHandler
    public void onDeadLetter(
            ConsumerRecord<String, byte[]> record,
            @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String reason) {
        log.error(
                "DEAD LETTER on {}-{} at offset {} (key={}): {} — no notification was written and "
                        + "nothing will retry it",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                reason);
    }
}

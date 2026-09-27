package com.ecomdemo.notification.internal;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topics this service OWNS: its two retry topics, and nothing else.
 *
 * <p><strong>One owner per topic.</strong> {@code orders.placed} and its DLT belong to the producer,
 * order-service ({@code ecomdemo-app}'s {@code KafkaTopicsConfig}), which declares the main topic with
 * THREE partitions. Until this class existed, {@code @RetryableTopic} created topics here too — the
 * retry topics, the DLT, and the main topic itself, with the broker's default of ONE partition. Two
 * services creating one topic with different partition counts is a race, and on a cold start it was
 * lost about one time in three:
 *
 * <ol>
 *   <li>this service started first and created {@code orders.placed} with one partition;</li>
 *   <li>order-service started a few seconds later, found it, and ADDED partitions 1 and 2;</li>
 *   <li>this service's consumer had already been assigned partition 0 and did not see the new ones
 *       until its next metadata refresh — five minutes by default. Every order keyed onto partitions 1
 *       and 2 sat unread until then, or until something forced a rebalance.</li>
 * </ol>
 *
 * Forcing that order on a fresh broker delivered 1 event of 9 within a minute; the consumer group had
 * no assignment for partitions 1 or 2 at all. There is also a quieter cost: adding partitions changes
 * which partition a key hashes to, so one order's events could have been split across two.
 *
 * <p>So {@code @RetryableTopic} now has {@code autoCreateTopics = "false"}, and this class declares the
 * two topics that really are this service's implementation detail. ONE partition each, as before: a
 * record forwarded to a retry topic whose partition does not exist is sent without one, and the retry
 * listener reads them in the order they failed.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

    /** Spring Kafka's names for attempts two and three: index-suffixed, one topic per distinct delay. */
    static final String FIRST_RETRY = Topics.ORDERS_PLACED + Topics.RETRY_SUFFIX + "-0";
    static final String SECOND_RETRY = Topics.ORDERS_PLACED + Topics.RETRY_SUFFIX + "-1";

    @Bean
    NewTopic firstRetryTopic() {
        return TopicBuilder.name(FIRST_RETRY).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic secondRetryTopic() {
        return TopicBuilder.name(SECOND_RETRY).partitions(1).replicas(1).build();
    }
}

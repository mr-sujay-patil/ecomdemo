package com.ecomdemo.messaging.internal;

import com.ecomdemo.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates the topics at startup, rather than letting a producer's first send create them.
 *
 * <p>Spring's {@code KafkaAdmin} applies every {@link NewTopic} bean it finds when the context
 * starts: it creates what is missing and leaves what exists alone (it will add partitions to an
 * existing topic, but never remove them and never change replication). The broker in compose has
 * {@code auto.create.topics.enable=false} precisely so that this is the only way a topic comes
 * into existence — otherwise a typo in a topic name invents a new topic with default settings and
 * looks like it worked, and the messages sit somewhere nobody is reading.
 *
 * <p><strong>Partitions are the unit of parallelism and of ordering, and the two are the same
 * decision.</strong> Kafka guarantees order within a partition and says nothing across them, so a
 * consumer group can have at most one consumer per partition doing useful work. Three partitions
 * here means up to three notification consumers; a fourth would sit idle. It also means messages
 * for different orders can be processed out of order relative to each other, which is fine because
 * nothing about a notification depends on another order's — and the key (see
 * {@code OutboxBatchPublisher}, which keys by the aggregate id) is what keeps one order's own
 * events together if a later phase adds a second one.
 */
@Configuration
public class KafkaTopicsConfig {

    /**
     * One replica, because there is one broker. Stated as a constant with this comment rather
     * than left as a literal 1, because it is the single line that would change first in any real
     * deployment: three brokers, {@code replicas(3)}, and {@code min.insync.replicas=2} so that a
     * write is acknowledged only once it survives the loss of any one of them. Nothing in the
     * application code changes with it.
     */
    private static final int REPLICAS = 1;

    private static final int PARTITIONS = 3;

    @Bean
    NewTopic ordersPlacedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_PLACED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /**
     * The dead-letter topic, declared explicitly.
     *
     * <p>This service is the ONE owner of {@code orders.placed} and its DLT. notification-service's
     * {@code @RetryableTopic} used to create them as well - the main topic with ONE partition - and
     * on a cold start whichever service came up first won. When notification-service won, this
     * class then added partitions 1 and 2 to a topic its consumer was already reading, and the
     * consumer did not see them for minutes. notification-service now creates only its own retry
     * topics. Declaring the DLT here also means it exists from startup, empty, which is what lets
     * the smoke test assert that it is empty rather than that it is absent.
     *
     * <p>ONE partition, not three: nothing consumes a DLT, and its entire purpose is that a human
     * reads it in order.
     */
    @Bean
    NewTopic ordersPlacedDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_PLACED + KafkaTopics.DLT_SUFFIX)
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }

    /**
     * The saga's first topic (Phase 24): a checkout created a PENDING order. inventory-service
     * reads it. Keyed by order id, three partitions, like {@code orders.placed} - and for the same
     * reason: one order's events stay in order, different orders run in parallel.
     */
    @Bean
    NewTopic ordersCreatedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_CREATED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /** Its dead-letter topic, declared here because this service is the publisher (one owner). */
    @Bean
    NewTopic ordersCreatedDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_CREATED + KafkaTopics.DLT_SUFFIX)
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }
}

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
     * <p>{@code @RetryableTopic} would create this and the retry topics itself, but only at the
     * moment they are first needed — so a freshly started stack has no DLT until something has
     * already failed, and "is there a DLT?" cannot be answered by looking. Declaring it here means
     * it exists from startup, empty, which is also what lets the smoke test assert that it is
     * empty rather than that it is absent.
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
}

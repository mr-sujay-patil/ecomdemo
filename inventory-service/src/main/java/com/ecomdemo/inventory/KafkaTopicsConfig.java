package com.ecomdemo.inventory;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates {@code inventory.stock-changed} at startup, because nothing else will.
 *
 * <p>The broker in compose runs with {@code auto.create.topics.enable=false} — a Phase 17 decision,
 * so that a typo in a topic name is an error rather than a new topic nobody is reading. That has a
 * consequence the extraction makes newly important: a topic with no {@code NewTopic} bean anywhere
 * simply does not exist, and both the producer and the consumer wait for something that is never
 * coming.
 *
 * <p><strong>The PUBLISHER declares it, not the consumer.</strong> Two services now care about this
 * topic, and if both declared it they would be two places that have to agree on the partition count
 * — a disagreement the broker resolves by ignoring the second one, silently. The service that owns
 * the fact owns the topic it is published on; a consumer is entitled to expect it to exist.
 *
 * <p>ONE partition, unlike {@code orders.placed}'s three. Ordering matters here: two changes to one
 * product's stock must be evicted in the order they happened, and Kafka guarantees order only
 * within a partition. Keying by product id would be enough to guarantee that with more partitions,
 * and one is chosen anyway because this topic carries a cache eviction rather than work — there is
 * no consumer parallelism to buy.
 */
@Configuration
class KafkaTopicsConfig {

    /** One broker in compose, so one replica. The line that changes first in a real deployment. */
    private static final int REPLICAS = 1;

    @Bean
    NewTopic stockChangedTopic() {
        return TopicBuilder.name(StockChangePublisher.TOPIC)
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }
}

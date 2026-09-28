package com.ecomdemo.catalog.search;

import com.ecomdemo.catalog.ProductChanged;
import com.ecomdemo.outbox.OutboxRoutes;
import com.ecomdemo.outbox.SagaListenerErrors;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * The messaging half of the embedding sync (Phase 28): the topic this service now publishes, its
 * dead-letter topic, and where the outbox sends each event.
 *
 * <p>The PUBLISHER declares its topics, as inventory-service does for its saga events. Three
 * partitions, keyed by product id: events about one product stay in order on one partition, and up
 * to three indexer instances can share the work.
 */
@Configuration
class SearchMessagingConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICAS = 1;

    @Bean
    NewTopic productChangedTopic() {
        return TopicBuilder.name(ProductChanged.TOPIC).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic productChangedDltTopic() {
        return TopicBuilder.name(ProductChanged.TOPIC + SagaListenerErrors.DLT_SUFFIX)
                .partitions(1).replicas(REPLICAS).build();
    }

    @Bean
    OutboxRoutes outboxRoutes() {
        return OutboxRoutes.builder().route(ProductChanged.class, ProductChanged.TOPIC).build();
    }

    /**
     * Retry, then dead-letter: the saga's error handler, reused. Boot installs a single
     * {@code CommonErrorHandler} bean into its DEFAULT listener container factory, which is the one
     * {@link ProductIndexer} uses. The cache evictor has its own factory and keeps its own
     * behaviour.
     */
    @Bean
    CommonErrorHandler indexerErrorHandler(SagaListenerErrors errors) {
        return errors.errorHandler();
    }
}

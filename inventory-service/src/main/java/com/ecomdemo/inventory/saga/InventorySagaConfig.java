package com.ecomdemo.inventory.saga;

import com.ecomdemo.outbox.OutboxRoutes;
import com.ecomdemo.outbox.SagaListenerErrors;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * inventory-service's side of the saga's wiring: the topics it OWNS, where its outbox events
 * go, and what its listeners do with a message they cannot handle.
 *
 * <p>Topic ownership follows the pre-Phase-23 rule: the PUBLISHER declares a topic and its DLT.
 * So this class declares the two topics inventory publishes, and not the two it reads -
 * {@code orders.created} belongs to the order service and {@code payments.failed} to
 * payment-service, which declare them (and their DLTs, which this service writes to).
 *
 * <p>THREE partitions, like {@code orders.placed}: keyed by order id, so one order's events stay
 * in order while different orders are handled in parallel. ONE for each DLT: nothing reads it at
 * speed, and {@code SagaListenerErrors} lets the producer pick the partition.
 */
@Configuration(proxyBeanMethods = false)
class InventorySagaConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICAS = 1;

    @Bean
    NewTopic stockReservedTopic() {
        return TopicBuilder.name(SagaTopics.STOCK_RESERVED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic stockReservedDltTopic() {
        return TopicBuilder.name(SagaTopics.STOCK_RESERVED + SagaListenerErrors.DLT_SUFFIX)
                .partitions(1).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic stockRejectedTopic() {
        return TopicBuilder.name(SagaTopics.STOCK_REJECTED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic stockRejectedDltTopic() {
        return TopicBuilder.name(SagaTopics.STOCK_REJECTED + SagaListenerErrors.DLT_SUFFIX)
                .partitions(1).replicas(REPLICAS).build();
    }

    @Bean
    OutboxRoutes outboxRoutes() {
        return OutboxRoutes.builder()
                .route(StockReservedEvent.class, SagaTopics.STOCK_RESERVED)
                .route(StockRejectedEvent.class, SagaTopics.STOCK_REJECTED)
                .build();
    }

    /** Boot installs a single CommonErrorHandler bean into its default listener container factory. */
    @Bean
    CommonErrorHandler sagaErrorHandler(SagaListenerErrors errors) {
        return errors.errorHandler();
    }
}

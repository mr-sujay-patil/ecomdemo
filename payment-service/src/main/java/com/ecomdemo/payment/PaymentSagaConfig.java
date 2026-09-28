package com.ecomdemo.payment;

import com.ecomdemo.outbox.OutboxRoutes;
import com.ecomdemo.outbox.SagaListenerErrors;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * The topics payment-service owns (it publishes them, so it declares them and their DLTs), where
 * its outbox events go, and its listener's error handling. See inventory's
 * {@code InventorySagaConfig} for the partition counts.
 */
@Configuration(proxyBeanMethods = false)
class PaymentSagaConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICAS = 1;

    @Bean
    NewTopic paymentsCompletedTopic() {
        return TopicBuilder.name(PaymentTopics.PAYMENTS_COMPLETED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic paymentsCompletedDltTopic() {
        return TopicBuilder.name(PaymentTopics.PAYMENTS_COMPLETED + SagaListenerErrors.DLT_SUFFIX)
                .partitions(1).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic paymentsFailedTopic() {
        return TopicBuilder.name(PaymentTopics.PAYMENTS_FAILED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    NewTopic paymentsFailedDltTopic() {
        return TopicBuilder.name(PaymentTopics.PAYMENTS_FAILED + SagaListenerErrors.DLT_SUFFIX)
                .partitions(1).replicas(REPLICAS).build();
    }

    @Bean
    OutboxRoutes outboxRoutes() {
        return OutboxRoutes.builder()
                .route(PaymentCompletedEvent.class, PaymentTopics.PAYMENTS_COMPLETED)
                .route(PaymentFailedEvent.class, PaymentTopics.PAYMENTS_FAILED)
                .build();
    }

    @Bean
    CommonErrorHandler sagaErrorHandler(SagaListenerErrors errors) {
        return errors.errorHandler();
    }
}

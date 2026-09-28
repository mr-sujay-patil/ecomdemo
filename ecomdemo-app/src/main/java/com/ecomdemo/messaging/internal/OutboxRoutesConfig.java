package com.ecomdemo.messaging.internal;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.messaging.OrderCreatedEvent;
import com.ecomdemo.messaging.OrderPlacedEvent;
import com.ecomdemo.outbox.OutboxRoutes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where each of the application's outbox events is published.
 *
 * <p>Until Phase 24 this mapping was an {@code if} inside the relay. The relay is a library now,
 * and a library cannot know this application's events - so the application tells it.
 */
@Configuration(proxyBeanMethods = false)
class OutboxRoutesConfig {

    @Bean
    OutboxRoutes outboxRoutes() {
        return OutboxRoutes.builder()
                .route(OrderCreatedEvent.class, KafkaTopics.ORDERS_CREATED)
                .route(OrderPlacedEvent.class, KafkaTopics.ORDERS_PLACED)
                .build();
    }
}

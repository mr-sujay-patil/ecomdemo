package com.ecomdemo.notification.internal;

import com.ecomdemo.outbox.OutboxRoutes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbox library's one required input: where each event type it relays should go.
 *
 * <p>This service publishes nothing, so the answer is NO routes. It uses the library only for
 * {@code ProcessedEvents} (KI-010), but {@code @EnableOutbox} brings the whole library, and the
 * relay insists on a routing table to start. An empty one is the honest description: the
 * {@code outbox_event} table is empty for ever and the relay finds nothing to do. The first event
 * this service publishes should be added here, not by building a second path around the outbox.
 */
@Configuration(proxyBeanMethods = false)
class OutboxWiring {

    @Bean
    OutboxRoutes outboxRoutes() {
        return OutboxRoutes.builder().build();
    }
}

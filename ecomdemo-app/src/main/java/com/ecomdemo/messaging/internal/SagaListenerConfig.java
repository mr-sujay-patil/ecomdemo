package com.ecomdemo.messaging.internal;

import com.ecomdemo.outbox.SagaListenerErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * What the order service's saga listeners do with a message they cannot handle at all: retry it in
 * place, then dead-letter it (Phase 24). {@code SagaListenerErrors} explains why in place rather
 * than on retry topics. Boot installs this single handler into its default container factory.
 */
@Configuration(proxyBeanMethods = false)
class SagaListenerConfig {

    @Bean
    CommonErrorHandler sagaErrorHandler(SagaListenerErrors errors) {
        return errors.errorHandler();
    }
}

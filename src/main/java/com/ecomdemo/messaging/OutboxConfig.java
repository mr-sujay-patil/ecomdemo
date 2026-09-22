package com.ecomdemo.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code ecomdemo.outbox.*} into {@link OutboxProperties}.
 *
 * <p>Deliberately holds nothing else. The relay's producer is constructed inside
 * {@link OutboxKafkaSender} rather than declared here as a bean — see that class for the two ways
 * a second {@code KafkaTemplate} bean breaks the application, neither of which any test caught.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfig {
}

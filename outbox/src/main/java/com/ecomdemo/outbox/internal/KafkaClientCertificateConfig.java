package com.ecomdemo.outbox.internal;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Keeps a service's Kafka client certificate current after a renewal, without a restart (Phase 35).
 *
 * <p>Active only when the service's Kafka clients use an SSL bundle ({@code spring.kafka.ssl.bundle}, set
 * in Kubernetes, where Kafka requires a client certificate). It replaces the bundle Boot put into its
 * producer and consumer factories with a {@link CurrentSslBundle} of the same name. Every other Kafka
 * client in the services copies its settings from those two factories (the outbox relay, the dead-letter
 * recoverer, ecomdemo-app's dead-letter reader, catalog-service's stock-changed consumer), so they get it
 * too. Not covered: Boot's {@code KafkaAdmin}, which creates the topics once at start-up, with the
 * certificate of that moment.
 *
 * <p>Picked up by {@code OutboxConfig}'s component scan, so every service with {@code @EnableOutbox} (all
 * five Kafka clients) has it. Public only so that tests can build a service's Kafka clients as the service does.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.kafka.ssl.bundle")
public class KafkaClientCertificateConfig {

    /** The key under which Boot's Kafka auto-configuration passes the bundle to its SSL engine factory. */
    static final String BUNDLE_CONFIG = SslBundle.class.getName();

    @Bean
    DefaultKafkaProducerFactoryCustomizer currentKafkaBundleForProducers(SslBundles bundles, Environment environment) {
        Map<String, Object> current = current(bundles, environment);
        return factory -> {
            if (factory.getConfigurationProperties().containsKey(BUNDLE_CONFIG)) {
                factory.updateConfigs(current);
            }
        };
    }

    @Bean
    DefaultKafkaConsumerFactoryCustomizer currentKafkaBundleForConsumers(SslBundles bundles, Environment environment) {
        Map<String, Object> current = current(bundles, environment);
        return factory -> {
            if (factory.getConfigurationProperties().containsKey(BUNDLE_CONFIG)) {
                factory.updateConfigs(current);
            }
        };
    }

    private static Map<String, Object> current(SslBundles bundles, Environment environment) {
        String name = environment.getRequiredProperty("spring.kafka.ssl.bundle");
        bundles.getBundle(name); // fails at start-up, by name, if the bundle does not exist
        return Map.of(BUNDLE_CONFIG, new CurrentSslBundle(bundles, name));
    }
}

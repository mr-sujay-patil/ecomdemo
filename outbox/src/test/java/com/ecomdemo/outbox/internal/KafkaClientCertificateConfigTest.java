package com.ecomdemo.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

/**
 * A renewed Kafka client certificate reaches the next connection (Phase 35).
 *
 * <p>Boot puts the SSL bundle object it found at start-up into its Kafka factories; a reloaded bundle is a new
 * object in the registry, which the factories would never see. {@link KafkaClientCertificateConfig} gives them
 * a view of the registry's current bundle instead. {@code KafkaClientAuthIT} proves it against a real broker.
 */
@DisplayName("The Kafka clients' SSL bundle follows a reload")
class KafkaClientCertificateConfigTest {

    private final DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaClientCertificateConfig.class)
            .withBean(SslBundles.class, () -> registry)
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:1");

    @Test
    @DisplayName("the producer and consumer factories carry the registry's current bundle, before and after a reload")
    void factoriesFollowTheRegistry() {
        SslBundle started = SslBundle.of(SslStoreBundle.of(null, null, null), null, null, "TLSv1.2");
        SslBundle renewed = SslBundle.of(SslStoreBundle.of(null, null, null), null, null, "TLSv1.3");
        registry.registerBundle("kafka", started);

        runner.withPropertyValues("spring.kafka.ssl.bundle=kafka").run(context -> {
            SslBundle producers = bundle(context.getBean(DefaultKafkaProducerFactory.class).getConfigurationProperties()
                    .get(KafkaClientCertificateConfig.BUNDLE_CONFIG));
            SslBundle consumers = bundle(context.getBean(DefaultKafkaConsumerFactory.class).getConfigurationProperties()
                    .get(KafkaClientCertificateConfig.BUNDLE_CONFIG));

            assertThat(producers).isInstanceOf(CurrentSslBundle.class);
            assertThat(consumers).isInstanceOf(CurrentSslBundle.class);
            assertThat(producers.getStores()).isSameAs(started.getStores());
            assertThat(producers.getProtocol()).isEqualTo("TLSv1.2");

            // What Boot does when the certificate files change (reload-on-update).
            registry.updateBundle("kafka", renewed);

            assertThat(producers.getStores()).isSameAs(renewed.getStores());
            assertThat(consumers.getStores()).isSameAs(renewed.getStores());
            assertThat(consumers.getProtocol()).isEqualTo("TLSv1.3");
        });
    }

    @Test
    @DisplayName("without spring.kafka.ssl.bundle (compose, the tests) nothing changes")
    void inactiveWithoutABundle() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("currentKafkaBundleForProducers");
            assertThat(context.getBean(DefaultKafkaProducerFactory.class).getConfigurationProperties())
                    .doesNotContainKey(KafkaClientCertificateConfig.BUNDLE_CONFIG);
        });
    }

    @Test
    @DisplayName("a bundle name with no bundle stops the start-up, naming it")
    void unknownBundleFailsFast() {
        runner.withPropertyValues("spring.kafka.ssl.bundle=missing").run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining("missing"));
    }

    private static SslBundle bundle(Object value) {
        return (SslBundle) value;
    }
}

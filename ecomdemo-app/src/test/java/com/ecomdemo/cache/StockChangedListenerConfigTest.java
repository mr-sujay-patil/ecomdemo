package com.ecomdemo.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/**
 * The stock-changed listener must read its own message type, and must not take the default
 * factory's name while doing it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A regression that every instrument called healthy. {@code spring.json.value.default.type} is
 * global and set to {@code OrderPlacedEvent}; the producer sends no type header; so messages on
 * {@code inventory.stock-changed} were deserialised as order events with every field null and
 * rejected by the listener. The consumer group showed <strong>zero lag</strong> the entire time,
 * because the records were consumed, failed, retried, exhausted and dropped — the offset advanced
 * exactly as it does when everything works. The only visible symptom was a catalogue quietly
 * advertising stock it had already sold.
 *
 * <p>So the first assertion below deserialises a real stock-changed payload and insists on getting
 * a stock change back. It fails against the shared default factory, which is the point.
 */
@DisplayName("The stock-changed listener's container factory")
class StockChangedListenerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(StockChangedListenerConfig.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:1",
                    // The global default that caused the bug, set here deliberately so the test
                    // runs against the configuration the application actually has. Without this
                    // line the test would pass for the wrong reason.
                    "spring.kafka.consumer.properties.spring.json.value.default.type="
                            + "com.ecomdemo.messaging.OrderPlacedEvent",
                    "spring.kafka.consumer.properties.spring.json.trusted.packages=com.ecomdemo.*");

    @Test
    @DisplayName("reads a stock change as a stock change, not as the globally defaulted order event")
    @SuppressWarnings("unchecked")
    void deserialisesTheRightType() {
        runner.run(context -> {
            var factory = (ConcurrentKafkaListenerContainerFactory<String, ProductCacheEvictor.ProductStockChanged>)
                    context.getBean(StockChangedListenerConfig.FACTORY);
            var consumers = (DefaultKafkaConsumerFactory<String, ProductCacheEvictor.ProductStockChanged>)
                    factory.getConsumerFactory();

            // createConsumer() rather than getValueDeserializer() alone, because CONFIGURING the
            // deserializers is what building a consumer does, and it is where this can fail. The
            // first version of this test skipped that step and passed against a configuration that
            // would not start: Spring Kafka throws "JsonDeserializer must be configured with
            // property setters, or via configuration properties; not both" at consumer creation,
            // which no amount of inspecting the un-configured instance would ever reach. It does
            // not connect to a broker - a KafkaConsumer resolves its cluster lazily on first use.
            consumers.createConsumer().close();

            Deserializer<ProductCacheEvictor.ProductStockChanged> values = consumers.getValueDeserializer();
            Object decoded = values.deserialize(
                    ProductCacheEvictor.STOCK_CHANGED_TOPIC,
                    "{\"productId\":42}".getBytes(StandardCharsets.UTF_8));

            assertThat(decoded)
                    .as("the payload must come back as the type this topic actually carries")
                    .isInstanceOf(ProductCacheEvictor.ProductStockChanged.class);
            assertThat(((ProductCacheEvictor.ProductStockChanged) decoded).productId()).isEqualTo(42L);
        });
    }

    @Test
    @DisplayName("does not take the name Boot's own auto-configured factory is conditional on")
    void leavesTheDefaultFactoryAlone() {
        // The Phase 18 KafkaTemplate mistake in its listener form. Boot auto-configures
        // `kafkaListenerContainerFactory` under @ConditionalOnMissingBean(name = ...), so a second
        // factory taking that name would silently remove the one every other @KafkaListener uses -
        // and nothing would fail until a notification went unsent.
        assertThat(StockChangedListenerConfig.FACTORY).isNotEqualTo("kafkaListenerContainerFactory");

        runner.run(context -> assertThat(context)
                .as("Boot's default listener factory must still be there for the other listeners")
                .hasBean("kafkaListenerContainerFactory"));
    }
}

package com.ecomdemo.cache;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * A listener factory of its own for {@code inventory.stock-changed}, because the application's
 * default one is wired for a different message.
 *
 * <h2>The bug this exists to fix</h2>
 *
 * <p>{@code spring.json.value.default.type} is a GLOBAL consumer property, and the application sets
 * it to {@code OrderPlacedEvent} — correct, and invisible, while there was exactly one topic being
 * consumed. The producer sends no type header (by design: a type header is a Java class name on the
 * wire, which couples the two services' package layouts), so the consumer falls back to that
 * default for every topic it reads. The stock event was therefore deserialised as an
 * {@code OrderPlacedEvent}, every field of it null, and the listener rejected it with a
 * {@code MessageConversionException} on every retry.
 *
 * <p>What made it hard to see: the consumer group reported <strong>no lag</strong>. The messages
 * were being consumed, failed, retried, exhausted and discarded — so the offset advanced perfectly
 * while nothing was evicted. Every instrument said healthy; the only symptom was a catalogue
 * showing a stale number, ten minutes at a time.
 *
 * <p>This is the Phase 18 {@code KafkaTemplate} lesson again from the other direction: a global
 * default in a shared namespace is a decision made once and inherited by everything added later.
 * Spring Boot's {@code spring.kafka.*} properties configure ONE consumer factory, and the moment a
 * second message type arrives, "the default" has to become "the default for that one".
 *
 * <h2>Why a new factory rather than changing the properties</h2>
 *
 * <p>Because the notification consumer is right to want {@code OrderPlacedEvent}, and the next
 * topic will want a third thing. A per-listener factory scales; a shared default that everybody
 * edits does not.
 *
 * <p><strong>The bean name matters.</strong> It is deliberately NOT
 * {@code kafkaListenerContainerFactory}: Boot auto-configures that one under
 * {@code @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")}, so taking the name
 * would silently remove the factory every other listener relies on. Phase 18 lost an afternoon to
 * exactly that shape of mistake with {@code KafkaTemplate}, and {@code KafkaTemplateWiringTest}
 * exists because of it.
 */
@Configuration
class StockChangedListenerConfig {

    static final String FACTORY = "stockChangedListenerContainerFactory";

    @Bean(FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, ProductCacheEvictor.ProductStockChanged>
            stockChangedListenerContainerFactory(KafkaProperties properties) {

        // The JSON deserializer is told the ONE type this topic carries, and told not to look for a
        // type header - the producer does not send one, and `useTypeHeaders(false)` is what stops it
        // preferring a header over what it has been told here.
        JsonDeserializer<ProductCacheEvictor.ProductStockChanged> payload =
                new JsonDeserializer<>(ProductCacheEvictor.ProductStockChanged.class);
        payload.setUseTypeHeaders(false);

        // Wrapped the same way the default consumer is: a message that cannot be deserialised at
        // all must not become a poison record that stops the partition. ErrorHandlingDeserializer
        // turns the failure into a null payload plus a header the error handler can act on.
        Map<String, Object> config = properties.buildConsumerProperties();
        config.put(ConsumerConfig.GROUP_ID_CONFIG, ProductCacheEvictor.CACHE_GROUP);

        // EVERY `spring.json.*` and `spring.deserializer.*` KEY HAS TO GO, and leaving them in is
        // not a subtle misconfiguration - it is a refusal to start:
        //
        //   IllegalStateException: JsonDeserializer must be configured with property setters,
        //                          or via configuration properties; not both
        //
        // The properties above are the ones that made this listener read the wrong type, so they
        // are exactly what must not survive into this factory; and having set the type on the
        // deserializer by hand, the map may not also try to. Spring Kafka refuses the combination
        // rather than picking a winner, which is the right call - a deserializer configured twice
        // is a question nobody can answer by reading one file.
        config.keySet().removeIf(key ->
                key.startsWith("spring.json.") || key.startsWith("spring.deserializer."));

        DefaultKafkaConsumerFactory<String, ProductCacheEvictor.ProductStockChanged> consumers =
                new DefaultKafkaConsumerFactory<>(
                        config,
                        new ErrorHandlingDeserializer<>(new StringDeserializer()),
                        new ErrorHandlingDeserializer<>(payload));

        ConcurrentKafkaListenerContainerFactory<String, ProductCacheEvictor.ProductStockChanged>
                factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumers);
        return factory;
    }
}

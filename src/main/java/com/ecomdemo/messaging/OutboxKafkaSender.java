package com.ecomdemo.messaging;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Sends the outbox's bytes exactly as they were committed.
 *
 * <h2>Why the relay needs its own producer</h2>
 *
 * <p>The application's template is {@code KafkaTemplate<String, Object>} with a
 * {@code JsonSerializer}: hand it an object and it produces JSON. The relay does not have an
 * object — it has a {@code String} of JSON frozen inside the order's transaction. Passing that to
 * a JSON serializer would serialise a <em>string</em>, producing a quoted, escaped blob no
 * consumer can read. So the relay sends through a plain {@code StringSerializer}, and the bytes on
 * the topic are byte-for-byte the bytes in the {@code payload} column.
 *
 * <p>That is not a workaround; it is what makes the relay a dumb pipe. It never deserialises the
 * payload, never knows what an {@code OrderPlacedEvent} is, and therefore cannot publish anything
 * other than what was recorded.
 *
 * <h2>Why the template is NOT a bean, which is the part that was learned the hard way</h2>
 *
 * <p>The obvious shape is a second {@code @Bean} of type {@code KafkaTemplate}. It is wrong, in
 * two ways that compound, and the unit and integration suites were green for both of them.
 *
 * <p>First, Spring Boot auto-configures its {@code kafkaTemplate} under
 * {@code @ConditionalOnMissingBean(KafkaTemplate.class)} — <em>any</em> template bean, whatever
 * its name or generics, makes Boot back off. Declaring one here silently deleted the
 * application's own template from the context.
 *
 * <p>Second, and because of the first, Spring Kafka's {@code DeadLetterPublishingRecoverer} —
 * which resolves a template BY TYPE to publish to the retry and dead-letter topics — found only
 * this one. It then tried to send an {@code OrderPlacedEvent} through a {@code StringSerializer}
 * and threw {@code ClassCastException} <em>inside the recoverer</em>. The record could not be
 * moved anywhere, so its offset was never committed and the entire partition stopped:
 * head-of-line blocking, exactly what Phase 17's retry topics exist to prevent, reintroduced by a
 * bean three packages away. Only the smoke test caught it, because only the smoke test has two
 * templates and a poison message at the same time.
 *
 * <p>Keeping the template private to this class removes the whole class of problem. There is one
 * {@code KafkaTemplate} bean in the context, Boot's, and it is the one the retry machinery finds.
 * {@code KafkaTemplateWiringTest} asserts exactly that, so the mistake cannot come back quietly.
 *
 * <p>There is deliberately no second constructor for tests. Spring counts <em>all</em> declared
 * constructors when deciding which to use — a private one included — so adding one gives "No
 * default constructor found" at startup. {@link OutboxBatchPublisher}'s test mocks this class
 * instead, which is what it wanted anyway: the relay's bookkeeping is the subject there, not the
 * serializer.
 *
 * <h2>What it inherits, and from where</h2>
 *
 * <p>The producer properties are copied from the auto-configured {@link ProducerFactory} rather
 * than rebuilt from {@code KafkaProperties}. That matters under test: Boot's
 * {@code @ServiceConnection} supplies the container's random broker address as connection DETAILS,
 * applied while that factory is built and never written back into the properties object.
 * Rebuilding from properties would point the relay at {@code localhost:9092} while the rest of
 * the application talked to the container — a failure that appears only in tests and reads like a
 * broker problem. Copying the factory's own map also brings {@code acks=all},
 * {@code enable.idempotence=true} and the lowered {@code max.block.ms} along without restating
 * them, so they cannot drift from Phase 17's settings.
 */
@Component
class OutboxKafkaSender implements DisposableBean {

    private final DefaultKafkaProducerFactory<String, String> producerFactory;
    private final KafkaTemplate<String, String> template;

    OutboxKafkaSender(ProducerFactory<?, ?> applicationProducerFactory) {
        Map<String, Object> properties =
                new HashMap<>(applicationProducerFactory.getConfigurationProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        this.producerFactory = new DefaultKafkaProducerFactory<>(properties);
        this.template = new KafkaTemplate<>(producerFactory);
    }

    CompletableFuture<SendResult<String, String>> send(String topic, String key, String payload) {
        return template.send(topic, key, payload);
    }

    /**
     * A producer holds sockets and a background IO thread. Because this one is constructed here
     * rather than by Boot, closing it is this class's job — without this the connection would
     * outlive the context and shutdown would end with the broker complaining about it.
     */
    @Override
    public void destroy() {
        if (producerFactory != null) {
            producerFactory.destroy();
        }
    }
}

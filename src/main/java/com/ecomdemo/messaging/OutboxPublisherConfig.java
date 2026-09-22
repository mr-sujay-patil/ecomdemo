package com.ecomdemo.messaging;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * A producer that sends the outbox's bytes exactly as they were committed.
 *
 * <p><strong>Why a second {@code KafkaTemplate} at all.</strong> The application's main template
 * is {@code KafkaTemplate<String, Object>} with a {@code JsonSerializer}: hand it an object and it
 * turns it into JSON. The relay does not have an object — it has a {@code String} of JSON that
 * was frozen inside the order's transaction. Passing that string to the JSON serializer would
 * serialise a <em>string</em>, producing a quoted, escaped blob that no consumer can read.
 *
 * <p>So the relay gets a {@code KafkaTemplate<String, String>} whose value serializer is a plain
 * {@code StringSerializer}, and the bytes on the topic are byte-for-byte the bytes in the
 * {@code payload} column. That is not a workaround; it is the property that makes the relay a
 * dumb pipe. The relay never deserialises the payload, never knows what an {@code OrderPlacedEvent}
 * is, and therefore cannot publish anything other than what was committed. A relay that
 * deserialised and re-serialised would be a second chance to change the message — and a second
 * place for a version skew between what was recorded and what was sent.
 *
 * <p><strong>Everything else is inherited, and copied from the right place.</strong> The
 * properties are taken from the auto-configured {@link ProducerFactory} itself rather than
 * rebuilt from {@code KafkaProperties}, which matters more than it sounds: Boot's
 * {@code @ServiceConnection} supplies the broker address in the integration tests as connection
 * DETAILS, applied while that factory is built, and never written back into the properties
 * object. Rebuilding from the properties would produce a relay pointed at
 * {@code localhost:9092} while the rest of the application talked to a container on a random
 * port — a failure that appears only under test and looks like a broker problem.
 *
 * <p>Copying the factory's own map also means {@code acks=all},
 * {@code enable.idempotence=true} and the lowered {@code max.block.ms} arrive here without being
 * restated, so they cannot drift from Phase 17's settings.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxPublisherConfig {

    /** Distinguishes this template from the application's {@code <String, Object>} one. */
    public static final String OUTBOX_KAFKA_TEMPLATE = "outboxKafkaTemplate";

    @Bean(OUTBOX_KAFKA_TEMPLATE)
    KafkaTemplate<String, String> outboxKafkaTemplate(ProducerFactory<?, ?> producerFactory) {
        Map<String, Object> properties = new HashMap<>(producerFactory.getConfigurationProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }
}

package com.ecomdemo.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Kafka broker for the integration tests, alongside PostgreSQL and Redis.
 *
 * <p><strong>Why a real broker and not {@code @EmbeddedKafka}.</strong> Spring Kafka ships an
 * embedded broker, and it is faster to start. It is also a different thing: the claims this phase
 * makes are about partitions, offsets, consumer groups, redelivery and retry topics, and a test
 * that passes against an imitation of those proves the imitation. This is the same judgement
 * Phase 7 made when it replaced H2 with a PostgreSQL container, for the same reason — and Phase
 * 13 made again for Redis.
 *
 * <p>{@code KafkaContainer} from {@code testcontainers-kafka} runs the official
 * {@code apache/kafka} image in KRaft mode with no ZooKeeper, which is what compose runs too. The
 * image is pinned to the same version as the broker in {@code compose.yaml}: a test that passes
 * against a different broker version than the one the application is deployed with is a test with
 * an asterisk on it.
 *
 * <p>{@code @ServiceConnection} sets {@code spring.kafka.bootstrap-servers} from the container's
 * own random port, which is why {@code application-it.properties} deliberately does not name a
 * broker — the tests cannot accidentally talk to one running on the developer's machine.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfig {

    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.2.1");

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(KAFKA_IMAGE);
    }
}

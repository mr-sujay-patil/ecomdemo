package com.ecomdemo.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * A guard on the shape of the context, written after the shape broke.
 *
 * <p>Phase 18's first draft published the relay's producer as a second {@code KafkaTemplate} bean.
 * That did two things, and no test in the build noticed either:
 *
 * <ol>
 *   <li>Spring Boot auto-configures its {@code kafkaTemplate} under
 *       {@code @ConditionalOnMissingBean(KafkaTemplate.class)}, so the new bean made Boot back off
 *       and the application's own template simply stopped existing.
 *   <li>Spring Kafka's {@code DeadLetterPublishingRecoverer} resolves a template BY TYPE. It found
 *       the relay's {@code StringSerializer} one, tried to send an {@code OrderPlacedEvent}
 *       through it, and threw {@code ClassCastException} inside the recoverer — so a poison
 *       message could not be moved to a retry topic, its offset was never committed, and the
 *       partition stopped dead. Head-of-line blocking, reintroduced by a bean in another package.
 * </ol>
 *
 * <p>Only {@code scripts/smoke-test.sh} caught it, because catching it needs two templates and a
 * poison message in the same running system. This test is the cheap version of that lesson: it
 * cannot prove the recoverer works, but it does prove the precondition that made it fail is gone,
 * and it fails in seconds instead of in a ten-minute smoke run.
 *
 * <p>If a later phase genuinely needs a second template, this test failing is the prompt to name
 * it explicitly wherever it is resolved by type — not to loosen the assertion.
 */
@SpringBootTest
@DisplayName("Kafka template wiring")
class KafkaTemplateWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Boot's own kafkaTemplate exists, and the retry machinery needs it to")
    void bootsTemplateIsStillThere() {
        assertThat(context.containsBean("kafkaTemplate"))
                .as("Boot backs off its kafkaTemplate if any other KafkaTemplate bean is declared")
                .isTrue();
    }

    @Test
    @DisplayName("there is exactly ONE KafkaTemplate bean, so resolution by type is unambiguous")
    void thereIsOnlyOneTemplate() {
        assertThat(context.getBeanNamesForType(KafkaTemplate.class))
                .as("a second template silently re-points DeadLetterPublishingRecoverer")
                .containsExactly("kafkaTemplate");
    }

    @Test
    @DisplayName("the relay still has a sender, whose producer is private to it")
    void theRelayHasItsOwnSender() {
        // The relay does send with a StringSerializer - that requirement did not go away. It just
        // does it through a producer this bean owns rather than one the context can resolve.
        assertThat(context.getBeanNamesForType(OutboxKafkaSender.class)).hasSize(1);
    }
}

package com.ecomdemo.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.RetryableTopic;

/**
 * This service creates its own retry topics and NOT order-service's {@code orders.placed}.
 *
 * <p>Structural on purpose. The defect this pins is a startup RACE between two deployables — this
 * service creating the topic with one partition, order-service adding two more — and no test inside one
 * module can start two services in a chosen order. The race itself was reproduced against the real
 * stack (1 event of 9 delivered) and is described in {@link KafkaTopicsConfig}. What a unit test CAN
 * hold is the rule that removes the race: one owner per topic.
 */
@DisplayName("topic ownership")
class TopicOwnershipTest {

    @Test
    @DisplayName("@RetryableTopic does not auto-create topics - it would create the MAIN topic too")
    void theRetryableListenerCreatesNoTopics() throws NoSuchMethodException {
        Method listener = Arrays.stream(OrderPlacedListener.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RetryableTopic.class))
                .findFirst()
                .orElseThrow();

        assertThat(listener.getAnnotation(RetryableTopic.class).autoCreateTopics()).isEqualTo("false");
    }

    @Test
    @DisplayName("the topics declared here are the retry topics, and never orders.placed or its DLT")
    void onlyTheRetryTopicsAreDeclared() {
        KafkaTopicsConfig config = new KafkaTopicsConfig();

        assertThat(Arrays.asList(config.firstRetryTopic(), config.secondRetryTopic()))
                .extracting(NewTopic::name)
                .containsExactly("orders.placed-retry-0", "orders.placed-retry-1")
                .doesNotContain(Topics.ORDERS_PLACED, Topics.ORDERS_PLACED + Topics.DLT_SUFFIX);
    }
}

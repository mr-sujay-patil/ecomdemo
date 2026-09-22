package com.ecomdemo.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The defaults, which are what a plain {@code ./mvnw spring-boot:run} gets.
 *
 * <p>Worth asserting because a nonsensical value here is not a startup failure — it is a relay
 * that never ticks, or a batch of zero, and either one looks exactly like "the outbox is not
 * working" from outside.
 */
@DisplayName("OutboxProperties")
class OutboxPropertiesTest {

    @Test
    void fillsInEveryDefaultWhenNothingIsConfigured() {
        OutboxProperties properties = new OutboxProperties(null, 0, null, null);

        assertThat(properties.pollDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.batchSize()).isEqualTo(100);
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.cleanupCron()).isEqualTo("0 0 3 * * *");
    }

    @Test
    @DisplayName("refuses a poll delay of zero, which would spin the relay against the database")
    void rejectsAZeroPollDelay() {
        assertThat(new OutboxProperties(Duration.ZERO, 100, null, null).pollDelay())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("refuses a batch size of zero, which would publish nothing for ever")
    void rejectsAnEmptyBatch() {
        assertThat(new OutboxProperties(null, -5, null, null).batchSize()).isEqualTo(100);
    }

    @Test
    void keepsWhatIsConfigured() {
        OutboxProperties properties =
                new OutboxProperties(Duration.ofMillis(250), 25, Duration.ofDays(1), "0 0 4 * * *");

        assertThat(properties.pollDelay()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.batchSize()).isEqualTo(25);
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.cleanupCron()).isEqualTo("0 0 4 * * *");
    }
}

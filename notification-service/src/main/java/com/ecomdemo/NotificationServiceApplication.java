package com.ecomdemo;

import com.ecomdemo.metrics.MetricsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * notification-service: it tells people what happened, exactly once.
 *
 * <p><strong>The only service with no HTTP API of its own.</strong> It has a health probe and a metrics
 * endpoint and nothing else — no controller, no public path, nobody calls it. It reacts. That makes it
 * the clearest illustration in the system of why Kafka is here rather than another RestClient: the
 * order has no interest in whether a notification was sent, and blocking a checkout on one would make
 * a shopper wait for an email.
 *
 * <p>It is also the only service that can be down for an hour without anybody noticing at the time.
 * Messages queue on the topic; when it comes back it works through them, and the {@code processed_event}
 * ledger means a redelivery costs nothing. That is the property the whole of Phase 17 and 18 was for.
 *
 * <p>The scan list is the shortest of the five — no {@code clients}, because it calls no other service
 * and therefore needs no identity to do it with.
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecomdemo.notification",
        "com.ecomdemo.jwt",
        "com.ecomdemo.logging",
        "com.ecomdemo.shared"
})
@ConfigurationPropertiesScan(basePackages = {"com.ecomdemo.notification", "com.ecomdemo.jwt"})
@Import(MetricsConfig.class)
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

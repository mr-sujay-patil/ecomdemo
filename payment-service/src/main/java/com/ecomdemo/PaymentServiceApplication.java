package com.ecomdemo;

import com.ecomdemo.metrics.MetricsConfig;
import com.ecomdemo.outbox.EnableOutbox;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * payment-service (Phase 24): a MOCK payment provider and the saga's third step.
 *
 * <p>It listens for {@code StockReserved}, charges the order's total - or declines it - and
 * announces {@code PaymentCompleted} or {@code PaymentFailed}. Nothing calls it and it calls
 * nothing: in a choreographed saga every service only reacts to events, which is what lets it
 * be down for a while without anyone's request failing. Orders simply stay PENDING until it
 * comes back and catches up from its Kafka offsets.
 *
 * <p>The scan list is notification-service's, for the same reason: {@code common} is a library
 * of packages and a service names the ones it uses. {@code @EnableOutbox} adds the outbox.
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecomdemo.payment",
        "com.ecomdemo.jwt",
        "com.ecomdemo.logging",
        "com.ecomdemo.shared",
        "com.ecomdemo.tracing"
})
@ConfigurationPropertiesScan(basePackages = {"com.ecomdemo.payment", "com.ecomdemo.jwt"})
@Import(MetricsConfig.class)
@EnableOutbox
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}

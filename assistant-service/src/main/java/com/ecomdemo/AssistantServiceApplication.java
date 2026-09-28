package com.ecomdemo;

import com.ecomdemo.metrics.MetricsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * The shopping assistant (Phase 29).
 *
 * <p>{@code com.ecomdemo.clients} is deliberately NOT scanned: that package is how a service calls
 * another AS ITSELF, with a service token. This one never does - every call it makes carries the
 * token of the customer it is answering - so it has no service identity to mint, and the absence
 * is enforced by the scan rather than by discipline.
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecomdemo.assistant",
        "com.ecomdemo.jwt",
        "com.ecomdemo.logging",
        "com.ecomdemo.shared",
        "com.ecomdemo.tracing"
})
@ConfigurationPropertiesScan(basePackages = {"com.ecomdemo.assistant", "com.ecomdemo.jwt"})
@Import(MetricsConfig.class)
public class AssistantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantServiceApplication.class, args);
    }
}

package com.ecomdemo;

import com.ecomdemo.metrics.MetricsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * customer-service: accounts, and the only place a password is ever checked or a user token issued.
 *
 * <p><strong>This is the service that makes the others' statelessness possible.</strong> Every other
 * service verifies tokens and none of them can mint one for a person — that asymmetry is the security
 * model, and it only means anything because the encoder lives here and nowhere else.
 *
 * <p>Note what is NOT in the scan list, by comparison with catalog-service: no {@code cache} and no
 * Kafka. This service has one small table, reads it by primary key or unique index, and tells nobody
 * when it changes. Caching accounts would add staleness to the one thing a login must read fresh.
 *
 * <p><strong>It sits in {@code com.ecomdemo}, not in {@code com.ecomdemo.customer}</strong>, and that
 * is not a style choice. This service's code spans three sibling packages — {@code customer},
 * {@code auth} and {@code security} — and Spring Boot's test support finds the configuration by
 * searching UPWARDS from a test's own package. With the class in {@code customer}, every
 * {@code @WebMvcTest} under {@code auth} failed with "Unable to find a @SpringBootConfiguration by
 * searching packages upwards from the test". The root package is the only one that is an ancestor of
 * all three.
 *
 * <p>It does scan {@code com.ecomdemo.clients}, and that is worth explaining because it looks like it
 * should not need to. It calls no other service today. But {@code clients} is where
 * {@code ServiceIdentityConfig} lives, and the day customer-service needs to tell another service
 * anything, it must already have an identity to do it with. Leaving the package out would work now
 * and be a puzzle later.
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecomdemo.customer",
        "com.ecomdemo.auth",
        "com.ecomdemo.security",
        "com.ecomdemo.clients",
        "com.ecomdemo.jwt",
        "com.ecomdemo.logging",
        "com.ecomdemo.shared"
})
@ConfigurationPropertiesScan(basePackages = {"com.ecomdemo.customer", "com.ecomdemo.clients", "com.ecomdemo.jwt"})
@Import(MetricsConfig.class)
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}

package com.ecomdemo.inventory;

import com.ecomdemo.metrics.MetricsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * inventory-service: stock levels, and the only code allowed to change one.
 *
 * <p><strong>The scan list is the interesting part of this file.</strong> {@code
 * @SpringBootApplication} scans the package it is declared in, which is this service's own code and
 * nothing else — so every bean {@code common} contributes has to be named. They are named
 * individually rather than by scanning {@code com.ecomdemo} wholesale, because {@code common} also
 * carries {@code CheckoutMetrics}, and a service that never sells anything should not be publishing
 * a checkout counter stuck at zero for Prometheus to scrape and a dashboard to average in.
 *
 * <p>What each one brings:
 *
 * <ul>
 *   <li>{@code jwt} — the signing key and the decoder. Without it the resource-server chain in
 *       {@link SecurityConfig} cannot be built at all.
 *   <li>{@code logging} — the correlation-id filter and the request log. This is what makes a
 *       single shopper's click traceable across two services: the id arrives in a header from the
 *       application and is put back on this service's log lines.
 *   <li>{@code shared} — the exception handler, so a 404 from here has the same {@code ApiError}
 *       shape as a 404 from anywhere else, and the OpenAPI document.
 * </ul>
 *
 * <p>{@link MetricsConfig} is imported rather than scanned for the same reason the list exists: its
 * package holds the one bean that does not belong here. It tags every metric with the service name,
 * which is what lets one Prometheus tell the services apart.
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecomdemo.inventory",
        "com.ecomdemo.jwt",
        "com.ecomdemo.logging",
        "com.ecomdemo.shared"
})
@ConfigurationPropertiesScan(basePackages = {"com.ecomdemo.inventory", "com.ecomdemo.jwt"})
@Import(MetricsConfig.class)
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

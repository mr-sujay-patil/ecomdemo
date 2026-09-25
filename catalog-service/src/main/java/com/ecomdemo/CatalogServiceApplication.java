package com.ecomdemo;

import com.ecomdemo.catalog.SecurityConfig;
import com.ecomdemo.metrics.MetricsConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * catalog-service: what a product IS. How many there are belongs to inventory-service.
 *
 * <p><strong>The scan list is the interesting part</strong>, for the reason
 * {@code InventoryServiceApplication} gives: {@code @SpringBootApplication} scans only the package
 * it is declared in, so every bean {@code common} contributes has to be named. They are named
 * individually rather than by scanning {@code com.ecomdemo} wholesale, because {@code common} also
 * carries {@code CheckoutMetrics}, and a service that sells nothing should not publish a checkout
 * counter stuck at zero for Prometheus to scrape.
 *
 * <p>Two entries here that inventory-service does not have:
 *
 * <ul>
 *   <li>{@code com.ecomdemo.cache} — the Redis cache moved in with the catalogue. It is not a
 *       shared facility; it exists to make <em>these</em> reads fast, and 20a showed it cannot live
 *       in {@code common} because it reads catalog's DTOs and listens for inventory's stock event.
 *   <li>{@code com.ecomdemo.clients.inventory} — the client for inventory-service, which moved to
 *       {@code common} in this phase so that two services can call one API without two copies of
 *       the error mapping that keeps the split invisible to shoppers.
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecomdemo.catalog",
        "com.ecomdemo.cache",
        "com.ecomdemo.clients",
        "com.ecomdemo.jwt",
        "com.ecomdemo.logging",
        "com.ecomdemo.shared"
})
@ConfigurationPropertiesScan(basePackages = {
        "com.ecomdemo.catalog",
        "com.ecomdemo.cache",
        "com.ecomdemo.clients",
        "com.ecomdemo.jwt"
})
@Import(MetricsConfig.class)
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}

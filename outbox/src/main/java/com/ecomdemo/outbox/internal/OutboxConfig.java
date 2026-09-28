package com.ecomdemo.outbox.internal;

import com.ecomdemo.outbox.Outbox;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Everything {@code @EnableOutbox} switches on, in one place.
 *
 * <p>The relay's producer is still constructed inside {@link OutboxKafkaSender} rather than
 * declared here as a bean — see that class for the two ways a second {@code KafkaTemplate} bean
 * breaks an application, neither of which any test caught.
 *
 * <h2>Why each annotation is here (Phase 24, when this became a library)</h2>
 *
 * <ul>
 *   <li>{@code @ComponentScan} — inventory-service's application class lives in
 *       {@code com.ecomdemo.inventory} and only scans the packages it names, so it would never
 *       find the relay. The two filters are the ones {@code @SpringBootApplication} uses, so a
 *       test slice such as {@code @DataJpaTest} still leaves the relay out.
 *   <li>(not here, but beside it) {@link OutboxPackageRegistrar} — the same problem for JPA: Boot
 *       looks for entities and repositories only under the application's own package. The
 *       registrar ADDS the outbox's package to that list; an {@code @EntityScan} would REPLACE the
 *       list and hide the service's own entities.
 *   <li>{@code @EnableScheduling} — the relay and the cleanup are {@code @Scheduled}. In
 *       ecomdemo-app they worked only because the batch module's scheduler happened to enable
 *       scheduling; a library cannot rely on a neighbour's annotation.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
@ComponentScan(
        basePackageClasses = Outbox.class,
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(
                    type = FilterType.CUSTOM,
                    classes = AutoConfigurationExcludeFilter.class)
        })
public class OutboxConfig {
}

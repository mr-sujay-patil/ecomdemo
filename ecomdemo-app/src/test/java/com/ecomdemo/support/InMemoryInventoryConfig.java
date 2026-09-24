package com.ecomdemo.support;

import com.ecomdemo.inventory.InventoryGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts {@link InMemoryInventory} in front of the real HTTP client for every integration test.
 *
 * <p>Imported by {@code IntegrationTest} rather than by each class, for the reason that base class
 * already explains at length: every annotation is part of the context cache key, so adding this to
 * one subclass would give that subclass a context — and a PostgreSQL, a Redis and a Kafka — of its
 * very own. One import in one place keeps the whole integration suite sharing a single set of
 * containers.
 *
 * <p>{@code @Primary} rather than excluding the real bean: {@code InventoryClient} stays in the
 * context, which means the wiring that builds it is still exercised at startup. A test that never
 * constructs the real client would not notice the day its constructor started throwing.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryInventoryConfig {

    @Bean
    @Primary
    InventoryGateway inMemoryInventory() {
        return new InMemoryInventory();
    }
}

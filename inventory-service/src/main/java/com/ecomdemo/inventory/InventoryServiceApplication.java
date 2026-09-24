package com.ecomdemo.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The inventory service: how many of each product there are.
 *
 * <p><strong>The first service extracted, because it is the only leaf.</strong> Phase 20a removed
 * both edges into this module — {@code inventory -> catalog} inverted when stock became its own
 * table, and {@code order -> catalog} vanished when the cart started snapshotting — so what is
 * left depends on nothing but the shared library. It answers questions; it does not ask any.
 *
 * <p>That is why it could be extracted without writing an HTTP client in the same commit, and it
 * is worth noticing that the property was earned rather than found: the module was not a leaf
 * three commits ago.
 *
 * <p><strong>What it does not have</strong> is as informative as what it does. No Redis, no Batch,
 * no springdoc UI, no cart, no orders — the monolith carried all of those because something in it
 * needed each. A service carries what it needs, which is the difference between five JVMs fitting
 * on a 3.8 GB Docker ceiling and not.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

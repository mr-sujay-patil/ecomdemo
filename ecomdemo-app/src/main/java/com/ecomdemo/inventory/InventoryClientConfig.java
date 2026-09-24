package com.ecomdemo.inventory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link InventoryProperties}, the same way every other feature in this application
 * registers its own settings.
 *
 * <p>Needed because {@code @ConfigurationProperties} on a record does nothing on its own — it
 * describes a binding, and something has to ask for it. This project uses
 * {@code @EnableConfigurationProperties} on a configuration class per feature rather than one
 * {@code @ConfigurationPropertiesScan} at the root, so that each feature's settings appear in the
 * context because that feature asked for them.
 */
@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
class InventoryClientConfig {
}

/**
 * Meter names and the Micrometer configuration. Named constants exist here for the reason
 * {@code KafkaTopics} does: a meter name is a public interface with no compiler behind it.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Metrics",
        allowedDependencies = {})
package com.ecomdemo.metrics;

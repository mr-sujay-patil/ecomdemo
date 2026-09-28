package com.ecomdemo.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxPackageRegistrar")
class OutboxPackageRegistrarTest {

    private static final String OUTBOX = "com.ecomdemo.outbox";

    @Test
    @DisplayName("adds nothing for ecomdemo-app, whose package already contains the outbox")
    void appAlreadyCoversIt() {
        assertThat(OutboxPackageRegistrar.covers("com.ecomdemo", OUTBOX)).isTrue();
    }

    @Test
    @DisplayName("adds the outbox for inventory-service, whose package does not")
    void inventoryDoesNot() {
        assertThat(OutboxPackageRegistrar.covers("com.ecomdemo.inventory", OUTBOX)).isFalse();
    }

    @Test
    @DisplayName("is not fooled by a package that merely starts with the same letters")
    void prefixIsNotContainment() {
        // "com.ecomdemo.out" is a string prefix of the outbox's package, not a parent of it.
        assertThat(OutboxPackageRegistrar.covers("com.ecomdemo.out", OUTBOX)).isFalse();
    }
}

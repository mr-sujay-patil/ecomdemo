package com.ecomdemo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Test client addresses (KI-046)")
class ClientAddressesTest {

    @Test
    @DisplayName("a run never gets the same address twice, so one test's blocked client cannot be another's")
    void neverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertThat(seen.add(ClientAddresses.next())).as("address %d repeated", i).isTrue();
        }
    }

    @Test
    @DisplayName("every address is in TEST-NET-3")
    void staysInDocumentationRange() {
        for (int i = 0; i < 100; i++) {
            assertThat(ClientAddresses.next()).matches("203\\.0\\.113\\.(25[0-4]|2[0-4]\\d|1?\\d?\\d)");
        }
    }
}

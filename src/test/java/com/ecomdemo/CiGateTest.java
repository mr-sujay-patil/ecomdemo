package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TEMPORARY. Exists for exactly one commit, to prove that a red test blocks a pull request.
 *
 * <p>The claim "CI gates the merge" is worth nothing until it has been watched to fail. This
 * test is committed, the run is observed going red, and the commit is then reverted — the revert
 * is the next commit on this branch, so both halves stay in the history as evidence.
 */
class CiGateTest {

    @Test
    @DisplayName("deliberately fails, to prove CI blocks the merge")
    void deliberatelyFails() {
        assertThat("CI").isEqualTo("green — and this assertion is meant to fail");
    }
}

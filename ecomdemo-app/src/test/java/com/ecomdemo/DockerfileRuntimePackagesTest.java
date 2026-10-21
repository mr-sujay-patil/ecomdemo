package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runtime image takes Alpine's security fixes when it is built, not when the base image is
 * next pushed (KI-077).
 *
 * <h2>The failure this catches, which happened</h2>
 *
 * <p>{@code eclipse-temurin:21-jre-alpine} was last pushed on 2026-09-25. Alpine published
 * {@code libexpat 2.9.0-r0} for CVE-2026-77214 (HIGH) after that, and the runtime stage installed
 * nothing, so every image kept {@code 2.8.5-r0}: the scheduled image scan on {@code main} went red
 * on 2026-10-18 with no change in this repository, and stayed red until the base tag would be
 * rebuilt, a date this project does not control.
 *
 * <p>Two things have to hold, and each is a line that is easy to delete as "noise":
 *
 * <ul>
 *   <li>the runtime stage upgrades its packages before the application's layers, and
 *   <li>the publish job does not take that stage from its layer cache. A cached {@code apk
 *       upgrade} layer is as old as the first build that made it, so the image CI scans (built
 *       without a cache) and the image it publishes (built with one) would no longer be the same.
 * </ul>
 */
@DisplayName("Dockerfile runtime packages (KI-077)")
class DockerfileRuntimePackagesTest {

    private static final String RUNTIME_STAGE = "FROM eclipse-temurin:21-jre-alpine AS runtime";
    private static final String UPGRADE = "RUN apk upgrade --no-cache";

    @Test
    @DisplayName("the runtime stage upgrades its OS packages before it copies the application")
    void runtimeStageUpgradesItsPackages() throws IOException {
        String dockerfile = Files.readString(ProjectRoot.resolve("Dockerfile"));

        assertThat(dockerfile)
                .as("the runtime stage is found by its FROM line; if this fails, the stage was renamed")
                .contains(RUNTIME_STAGE);
        String runtime = dockerfile.substring(dockerfile.indexOf(RUNTIME_STAGE));

        assertThat(runtime)
                .as("the runtime stage must take Alpine's fixes at build time, not the base tag's")
                .contains(UPGRADE);
        assertThat(runtime.indexOf(UPGRADE))
                .as("upgrade before the first COPY, so a code change does not re-run it")
                .isLessThan(runtime.indexOf("\nCOPY "));
    }

    @Test
    @DisplayName("the published image rebuilds the runtime stage instead of taking it from the cache")
    void publishDoesNotCacheTheRuntimeStage() throws IOException {
        String ci = Files.readString(ProjectRoot.resolve(".github/workflows/ci.yml"));

        assertThat(ci)
                .as("ci.yml's publish job must rebuild the runtime stage, or it ships a stale upgrade")
                .contains("no-cache-filters: runtime");
    }
}

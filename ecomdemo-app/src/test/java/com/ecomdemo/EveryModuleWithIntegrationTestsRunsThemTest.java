package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A module with {@code *IT} tests must name the Failsafe plugin, or they never run.
 *
 * <h2>The silence this catches, which lasted two whole phases</h2>
 *
 * <p>The parent pom declares Failsafe in {@code <pluginManagement>}, so a module opts in by naming it —
 * the same arrangement as Surefire and for the same reason. {@code ecomdemo-app} names it. The four
 * services extracted in Phases 20b, 20c and 20d did not.
 *
 * <p>The consequence is the worst kind: {@code ./mvnw clean verify} printed BUILD SUCCESS the entire
 * time. A plugin that is not bound to a phase runs nothing and reports nothing — there is no "0 tests"
 * line to notice, no warning, no skipped count. {@code CacheApiIT}, {@code ProductApiIT},
 * {@code AuthApiIT} and {@code OrderPlacedConsumerIT} were written, reviewed, committed and described in
 * test reports as passing, and not one of them had ever been executed.
 *
 * <p>That is a sharper version of the lesson this project keeps relearning: <strong>a green build only
 * means the things that ran passed.</strong> The Dockerfile test exists because a list went stale; this
 * one exists because an absence was invisible. Both are checked by reading the build files rather than
 * by trusting anyone to remember.
 */
@DisplayName("Build configuration")
class EveryModuleWithIntegrationTestsRunsThemTest {

    @Test
    @DisplayName("every module containing *IT tests names the Failsafe plugin")
    void integrationTestsAreActuallyBound() throws IOException {
        String parentPom = Files.readString(ProjectRoot.resolve("pom.xml"));

        List<String> modules = new ArrayList<>();
        Matcher module = Pattern.compile("<module>([^<]+)</module>").matcher(parentPom);
        while (module.find()) {
            modules.add(module.group(1).trim());
        }
        assertThat(modules).as("the parent pom should still declare modules").isNotEmpty();

        List<String> unbound = new ArrayList<>();
        for (String name : modules) {
            Path tests = ProjectRoot.resolve(name + "/src/test");
            if (!Files.isDirectory(tests)) {
                continue;
            }
            long integrationTests;
            try (Stream<Path> walk = Files.walk(tests)) {
                integrationTests = walk.filter(p -> p.getFileName().toString().endsWith("IT.java")).count();
            }
            if (integrationTests == 0) {
                continue;
            }
            String pom = Files.readString(ProjectRoot.resolve(name + "/pom.xml"));
            if (!pom.contains("maven-failsafe-plugin")) {
                unbound.add(name + " (" + integrationTests + " *IT files, Failsafe not named)");
            }
        }

        assertThat(unbound)
                .as("these modules have integration tests that NEVER RUN - `verify` passes them by in "
                        + "silence, because an unbound plugin reports nothing at all")
                .isEmpty();
    }
}

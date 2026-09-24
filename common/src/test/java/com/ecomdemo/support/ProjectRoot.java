package com.ecomdemo.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds the repository root, for the tests that read files the build does not compile.
 *
 * <p>Several tests assert on configuration that lives beside the code rather than in it —
 * {@code compose.yaml}, Prometheus's alert rules, Alloy's pipeline, the Grafana dashboards. Those
 * files are a contract with the application that no compiler checks, which is exactly why the
 * tests exist; but they are addressed by path, and a path has to start somewhere.
 *
 * <p><strong>Phase 20 moved where that somewhere is.</strong> Maven runs a test with the working
 * directory set to its MODULE, and until this phase the module was the repository. Now it is
 * {@code ecomdemo-app/}, so every {@code Path.of("compose.yaml")} in the suite began resolving one
 * directory too deep and six tests failed at once, all of them reading files nobody had touched.
 *
 * <p>Walking up to find a marker is better than the obvious fix of writing {@code "../compose.yaml"}
 * everywhere. That spelling encodes the current depth of the module into every call site, so the
 * next time the layout moves — and this phase moves it again, five times, as the services are
 * carved out — every one of them is wrong again. This asks the question the paths actually mean:
 * where is the repository?
 *
 * <p>It moved to {@code common} in Phase 20b and is published as a TEST-JAR, because every service
 * extracted from here needs it and each one sits at a different depth. Its depth is exactly what
 * this class exists not to hard-code, so five copies would be five places to get the same thing
 * subtly wrong.
 */
public final class ProjectRoot {

    /**
     * {@code compose.yaml} is the marker because it has been at the root since Phase 10 and is not
     * the sort of file that gets a second copy in a subdirectory. A {@code .git} directory would
     * also work and would be wrong in a checkout that is a submodule or a worktree.
     */
    private static final String MARKER = "compose.yaml";

    private static final Path ROOT = findRoot();

    private ProjectRoot() {
    }

    /** Resolves a repository-relative path, wherever the test happens to be run from. */
    public static Path resolve(String relativePath) {
        return ROOT.resolve(relativePath);
    }

    private static Path findRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve(MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not find the repository root: no '" + MARKER + "' in "
                        + Path.of("").toAbsolutePath() + " or any directory above it.");
    }
}

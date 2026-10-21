package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The image scan's Trivy cache stays readable by the runner, so {@code actions/cache} can save it
 * (KI-078).
 *
 * <h2>The failure this catches, which happened</h2>
 *
 * <p>The scan ran Trivy as root in its container, with {@code $HOME/.cache/trivy} bind-mounted.
 * The directory did not exist before the first run, so Docker created it as root, and Trivy wrote
 * its database and {@code fanal/} as root. At the end of the job {@code actions/cache} runs
 * {@code tar} as the runner's user, got {@code Permission denied} on {@code fanal/}, and logged
 * {@code Failed to save} as a warning: the job stayed green, the cache was never saved, and every
 * run downloaded the vulnerability database again.
 *
 * <p>Both halves are needed: a directory the runner made, and a Trivy that writes as the runner.
 * Either one alone still leaves root-owned files behind.
 */
@DisplayName("ci.yml image scan (KI-078)")
class TrivyCacheOwnerTest {

    @Test
    @DisplayName("runs Trivy as the runner's user, on a cache directory the runner created first")
    void trivyWritesItsCacheAsTheRunner() throws IOException {
        String job = imageScanJob();
        String scan = job.substring(job.indexOf("- name: Scan every image"));

        assertThat(scan)
                .as("the runner must create the cache directory, or Docker creates it as root")
                .contains("mkdir -p \"$HOME/.cache/trivy\"");
        assertThat(scan.indexOf("mkdir -p \"$HOME/.cache/trivy\""))
                .as("the directory must exist before the first docker run mounts it")
                .isLessThan(scan.indexOf("docker run"));
        assertThat(scan)
                .as("Trivy must write as the runner's user, or actions/cache cannot read what it wrote")
                .contains("--user \"$(id -u):$(id -g)\"");
        assertThat(scan)
                .as("a non-root Trivy cannot use /root; the cache must be mounted where it can write")
                .doesNotContain(":/root/.cache/trivy");
    }

    private static String imageScanJob() throws IOException {
        String ci = Files.readString(ProjectRoot.resolve(".github/workflows/ci.yml"));
        Matcher start = Pattern.compile("(?m)^  image-scan:$").matcher(ci);
        assertThat(start.find()).as("ci.yml should still have the image-scan job").isTrue();
        Matcher next = Pattern.compile("(?m)^  [a-z-]+:$").matcher(ci);
        int end = next.find(start.end()) ? next.start() : ci.length();
        return ci.substring(start.start(), end);
    }
}

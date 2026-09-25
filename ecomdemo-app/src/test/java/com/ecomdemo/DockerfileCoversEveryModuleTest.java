package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every module the reactor builds must be copied into the Docker build context.
 *
 * <h2>The drift this catches, which happened</h2>
 *
 * <p>The Dockerfile copies module POMs one line at a time — deliberately, because that is what
 * gives the dependency layer its cache. It therefore has a list of modules in it, and a list in a
 * second place is a list that goes stale. Adding catalog-service to {@code pom.xml} and not to the
 * Dockerfile produced:
 *
 * <pre>Child module /build/catalog-service of /build/pom.xml does not exist</pre>
 *
 * <p>which is a clear message, arriving at the worst moment: not in the build, not in CI, but
 * minutes into {@code docker compose build}, after the whole test suite had passed. The next four
 * services would each have had one chance to make the same mistake.
 *
 * <p>This is the same shape as {@code alloyShipsEveryServiceThisRepositoryBuilds}: a list that must
 * agree with another list, checked by reading both rather than by remembering.
 */
@DisplayName("Dockerfile")
class DockerfileCoversEveryModuleTest {

    @Test
    @DisplayName("copies the pom and the sources of every module in the reactor")
    void copiesEveryReactorModule() throws IOException {
        String parentPom = Files.readString(ProjectRoot.resolve("pom.xml"));
        String dockerfile = Files.readString(ProjectRoot.resolve("Dockerfile"));

        List<String> modules = new ArrayList<>();
        Matcher module = Pattern.compile("<module>([^<]+)</module>").matcher(parentPom);
        while (module.find()) {
            modules.add(module.group(1).trim());
        }

        assertThat(modules)
                .as("the parent pom should still declare modules; if not, this test's parsing broke")
                .isNotEmpty();

        for (String name : modules) {
            assertThat(dockerfile)
                    .as("Dockerfile must COPY %s/pom.xml, or the reactor cannot resolve it", name)
                    .contains("COPY " + name + "/pom.xml " + name + "/");
            assertThat(dockerfile)
                    .as("Dockerfile must COPY %s/src/, or the module builds with no sources", name)
                    .contains("COPY " + name + "/src/ " + name + "/src/");
        }
    }
}

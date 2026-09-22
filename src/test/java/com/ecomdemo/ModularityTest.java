package com.ecomdemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * The architecture, asserted.
 *
 * <p>Every other test in this build checks what the code <em>does</em>. This one checks how it is
 * <em>arranged</em>, which is the only kind of decay that no amount of passing behaviour will
 * reveal: an import added across a boundary in a hurry compiles, passes, ships, and is discovered
 * two years later when somebody tries to extract a service and finds forty classes holding hands.
 *
 * <p>{@link ApplicationModules} reads the package structure under {@link EcomdemoApplication},
 * treats each direct sub-package as a module, and uses ArchUnit to check the rules. There is no
 * runtime involvement at all — no context, no beans, no database — which is why this and
 * {@code spring-modulith-docs} are test-scoped dependencies rather than application ones.
 *
 * <p><strong>The rule it enforces</strong> is that a module's {@code internal} sub-packages belong
 * to that module alone. Anything else may use a module's top-level types — its published API — and
 * nothing may reach past them. That is the difference between a package and a module: a package is
 * a folder, a module is a promise about what the rest of the system is allowed to know.
 *
 * <p>Written BEFORE any code was moved, on purpose. Running it against the Phase 18 layout is how
 * this phase found out what the real coupling was, instead of guessing a module map and
 * discovering the guess was wrong halfway through moving files. It found exactly two problems - a
 * {@code customer <-> security} cycle and one reach into a sub-package - which is a far better
 * starting point than the module map anybody would have drawn from memory.
 *
 * <p><strong>Since every module now DECLARES its allowed dependencies</strong> in its
 * {@code package-info.java}, this test checks more than internals: it checks the shape of the
 * graph. An import that adds an edge nobody declared fails here, naming both ends. That is the
 * difference between a diagram that describes the code and a diagram the code is held to.
 */
@DisplayName("Module boundaries")
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(EcomdemoApplication.class);

    @Test
    @DisplayName("no module reaches into another module's internals")
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    /**
     * Writes the module documentation, and fails if it cannot.
     *
     * <p>A test rather than a build plugin, because documentation generated from the code is only
     * worth having if it is regenerated when the code changes — and the surest way to make that
     * happen is for it to be part of the thing everyone already runs. The diagrams are committed
     * under {@code docs/modules/}, so a pull request that moves a dependency shows the moved arrow
     * in its diff.
     *
     * <p>It produces a C4 component diagram per module plus an overall one, in PlantUML, and an
     * Asciidoc canvas listing each module's published types and its dependencies. Nothing renders
     * them here; they are source, and GitHub renders PlantUML in a diff.
     *
     * <h2>Why the output is sorted afterwards</h2>
     *
     * <p>{@code Documenter} does not emit its relationship lines in a stable order — the same
     * unchanged code produces the same lines shuffled differently from run to run. Committed
     * output that reshuffles defeats the entire reason for committing it: every {@code ./mvnw
     * test} would dirty the working tree, and a real dependency change — the one thing these files
     * exist to make visible — would be one line lost among twenty reordered ones.
     *
     * <p>So each generated file has its {@code Rel(...)} lines sorted into a canonical order
     * before it is written. After this, a diff in {@code docs/modules/} means the GRAPH changed,
     * which is the only claim worth making about a generated file that lives in Git.
     */
    @Test
    @DisplayName("the module documentation regenerates from the code")
    void writesDocumentation() throws IOException {
        Path output = Path.of("docs/modules");
        Files.createDirectories(output);

        new Documenter(MODULES, output.toString())
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();

        try (var files = Files.list(output)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".puml")).toList()) {
                canonicalise(file);
            }
        }
    }

    /**
     * Sorts a PlantUML file's relationship lines, leaving everything else exactly where it is.
     *
     * <p>Only the {@code Rel(...)} block is unstable, and it is always contiguous — the component
     * declarations above it and the layout directives below it come out in a fixed order. So the
     * file is rewritten with that one run of lines sorted and every other line untouched, which
     * keeps the output something PlantUML still understands and something a human can still read
     * top to bottom.
     */
    private static void canonicalise(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        int first = -1;
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Rel(")) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return;
        }

        List<String> relations = new ArrayList<>(lines.subList(first, last + 1));
        Collections.sort(relations);

        List<String> rewritten = new ArrayList<>(lines.subList(0, first));
        rewritten.addAll(relations);
        rewritten.addAll(lines.subList(last + 1, lines.size()));
        Files.write(file, rewritten);
    }
}

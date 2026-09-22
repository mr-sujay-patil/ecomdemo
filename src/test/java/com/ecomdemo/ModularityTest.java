package com.ecomdemo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

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
 * discovering the guess was wrong halfway through moving files.
 */
@DisplayName("Module boundaries")
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(EcomdemoApplication.class);

    @Test
    @DisplayName("no module reaches into another module's internals")
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }
}

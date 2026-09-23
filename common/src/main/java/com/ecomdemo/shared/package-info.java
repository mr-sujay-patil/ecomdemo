/**
 * The small set of agreements several modules genuinely have to share: the API error shape, the
 * four domain exceptions the handler translates into it, the OpenAPI configuration, and the JWT
 * claim names.
 *
 * <p><strong>It depends on nothing, and that is the test of whether it deserves the name.</strong>
 * A shared module that depends on other modules is not shared, it is a layer - and a layer that
 * everything else already depends on becomes the place changes go to ripple. The empty
 * {@code allowedDependencies} below is what keeps it honest: adding an import from here to any
 * other module fails the build rather than being noticed in review, or not.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Shared kernel",
        allowedDependencies = {})
package com.ecomdemo.shared;

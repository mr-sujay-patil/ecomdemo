/**
 * What gets traced (Phase 23). Cross-cutting infrastructure: it decides which observations become
 * spans, and knows nothing about any domain.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Tracing",
        allowedDependencies = {})
package com.ecomdemo.tracing;

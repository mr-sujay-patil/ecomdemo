/**
 * Correlation IDs and the request log. Cross-cutting infrastructure that reads the servlet request
 * and writes to the MDC, and knows nothing about any domain.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Logging",
        allowedDependencies = {})
package com.ecomdemo.logging;

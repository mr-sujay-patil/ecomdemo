/**
 * The Redis client certificate (Phase 36). Cross-cutting infrastructure: it keeps the certificate a service
 * presents to Redis current after a renewal, and knows nothing about any domain.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Redis client certificate",
        allowedDependencies = {})
package com.ecomdemo.redis;

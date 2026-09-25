/**
 * Login, and issuing the token the rest of the system verifies.
 *
 * <p>Separate from {@code security}, which VERIFIES tokens: issuing and checking are different
 * jobs with different inputs, and only one of them needs a password.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Auth",
        allowedDependencies = {"jwt", "security", "shared"})
package com.ecomdemo.auth;

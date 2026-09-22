/**
 * The filter chain, the JWT decoder and the authorization rules.
 *
 * <p>It depends on {@code customer} because authenticating somebody means looking up their
 * account - through {@code UserDirectory}, not the repository. The reverse edge used to exist
 * too, and removing it was this phase's first commit.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Security",
        allowedDependencies = {"customer", "shared"})
package com.ecomdemo.security;

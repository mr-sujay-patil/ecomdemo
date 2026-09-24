/**
 * Token <em>validation</em>: the signing key, the decoder, and the properties both are built from.
 *
 * <p>It lives in {@code common} because since Phase 20b every service has to verify a token, and
 * none of them may call another service to do it — that is the entire point of a signed token, and
 * Phase 9 already made the check stateless. What is deliberately <em>not</em> here is the
 * {@code JwtEncoder}: issuing stays with the one module that owns logins.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "JWT keys",
        allowedDependencies = {})
package com.ecomdemo.jwt;

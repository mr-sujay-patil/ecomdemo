/**
 * Token <em>validation</em>: the signing key, the decoder, and the properties both are built from.
 *
 * <p>It lives in {@code common} because since Phase 20b every service has to verify a token, and
 * none of them may call another service to do it — that is the entire point of a signed token, and
 * Phase 9 already made the check stateless. What is deliberately <em>not</em> here is the
 * {@code JwtEncoder}: issuing stays with the one module that owns logins.
 *
 * <p><strong>It depends on {@code shared} since Phase 20d</strong>, where it declared no dependencies
 * before. The 401/403 handlers moved in here from the application's {@code security} module — every
 * service needs them, because every service refuses requests — and they write an {@code ApiError},
 * which is {@code shared}'s. That is the shared kernel being used as intended rather than a boundary
 * eroding: {@code shared} still depends on nothing, so nothing has become circular.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "JWT and token handling",
        allowedDependencies = {"shared"})
package com.ecomdemo.jwt;

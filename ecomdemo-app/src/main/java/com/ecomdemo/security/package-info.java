/**
 * The filter chain: what this application will and will not answer, and to whom.
 *
 * <p><strong>It no longer depends on {@code customer}</strong>, and that removal is Phase 20d in one
 * line. This module used to load an account to check a password — it held {@code AppUserDetailsService},
 * an {@code AuthenticationManager} and a {@code PasswordEncoder}, all of which needed the
 * {@code users} table. All three went to customer-service with it.
 *
 * <p>What is left is the half every service keeps: VERIFYING a token somebody else issued, and
 * deciding which paths need one. It depends on {@code jwt} for the decoder and the shared 401/403
 * writers, and on {@code shared} for the error shape. It cannot authenticate a credential, and there
 * is no bean here that could.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Security",
        allowedDependencies = {"jwt", "shared"})
package com.ecomdemo.security;

/**
 * Accounts and profiles, and the answer to "who is calling?".
 *
 * <p>{@code CurrentUser} lives here rather than in {@code security}, which is what broke this
 * application's only dependency cycle: security needs accounts to authenticate them, so customer
 * could not also need security. The question it answers - which {@code User} is acting - is a
 * customer question; reading the {@code SecurityContextHolder} is merely how it is answered.
 *
 * <p>{@code UserDirectory} is published for the modules that authenticate people;
 * {@code UserRepository} is not, because a repository is a module's entire data surface.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Customer",
        allowedDependencies = {"shared"})
package com.ecomdemo.customer;

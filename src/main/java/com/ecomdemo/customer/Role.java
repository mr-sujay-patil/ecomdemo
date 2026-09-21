package com.ecomdemo.customer;

/**
 * What a user is allowed to be. Two roles are enough for this application: somebody who shops,
 * and somebody who runs the shop.
 *
 * <p>A role is a coarse label, not a list of permissions. "Which endpoints may an ADMIN call?"
 * is answered in {@code SecurityConfig} and in the {@code @PreAuthorize} annotations, not here,
 * so the rules live next to the things they protect and this enum stays a closed, checkable set
 * of values — which is also what the {@code ck_users_role} constraint in V5 enforces.
 *
 * <p>Spring Security expects roles to appear as authorities prefixed with {@code ROLE_}: its
 * {@code hasRole("ADMIN")} is shorthand for {@code hasAuthority("ROLE_ADMIN")}. The prefix is a
 * Spring Security convention and has no business meaning, so it is added where the authorities
 * are built ({@code AppUserDetails}) and kept out of the database.
 */
public enum Role {
    CUSTOMER,
    ADMIN
}

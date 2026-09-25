package com.ecomdemo.support;

/**
 * Who a test is acting as: an id, a name, and a role.
 *
 * <p>It replaces the {@code TestAccount} ENTITY that the application's test data used to build. That entity
 * belongs to customer-service since Phase 20d, and this module cannot construct one — but the call
 * sites still want to say {@code cart(1L, customer())} rather than {@code cart(1L, 1L)}, which says
 * nothing about whose cart it is.
 *
 * <p>So this is a value, not a stand-in for an account. It carries exactly what a token carries and
 * exactly what cart and order store: the id, and the name for an audit row. There is no password, no
 * created-at and no profile, because nothing on this side of the split can see them.
 */
public record TestAccount(long id, String username, String role) {

    public static TestAccount customer(long id, String username) {
        return new TestAccount(id, username, "CUSTOMER");
    }

    public static TestAccount admin(long id, String username) {
        return new TestAccount(id, username, "ADMIN");
    }
}

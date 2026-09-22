package com.ecomdemo.security;

import com.ecomdemo.customer.User;
import com.ecomdemo.customer.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * "Who is calling?", answered in one place.
 *
 * <p>The authenticated principal lives in the {@code SecurityContextHolder}, which is a
 * {@code ThreadLocal}: the filter chain puts it there at the start of the request and clears it
 * at the end, so any code running on that request's thread can read it without it being threaded
 * through every method signature. That is convenient and it is also the catch — a background
 * thread, a {@code @Async} method or a scheduled job has a different thread and therefore an
 * empty context. Everything in this application that calls into here runs on the request thread.
 *
 * <p>Wrapping the static holder in a bean is what makes the services that use it testable: a
 * unit test injects a stub of this class instead of having to populate a thread-local.
 *
 * <p>{@link #require()} re-reads the row rather than handing back something built at login time.
 * The services that call it — the cart, checkout — need a <em>managed</em> entity inside their
 * own transaction so that JPA associations point at real, attached instances. The id comes off
 * the principal, so this is a primary-key lookup that Hibernate will usually serve from the
 * first-level cache of the transaction that already touched it.
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The id of the authenticated account. */
    public Long id() {
        return details().getId();
    }

    /** The username of the authenticated account. */
    public String username() {
        return details().getUsername();
    }

    /** The authenticated account as a managed entity in the caller's transaction. */
    public User require() {
        Long id = id();
        return userRepository
                .findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated as user " + id + " but that account no longer exists"));
    }

    /**
     * The principal, or a failure.
     *
     * <p>Reaching here without an authenticated user is a bug in the security rules, not a
     * client error: every path that leads here is behind an {@code authenticated()} rule in
     * {@code SecurityConfig}, so the filter chain should already have answered 401. Failing
     * loudly makes a hole in those rules show up as a 500 in the tests rather than as a
     * {@code NullPointerException} three frames deeper.
     */
    private AppUserDetails details() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails details)) {
            throw new IllegalStateException(
                    "No authenticated user on this thread. A protected endpoint was reached "
                            + "without authentication, or this ran off the request thread.");
        }
        return details;
    }
}

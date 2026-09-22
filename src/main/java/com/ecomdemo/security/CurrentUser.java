package com.ecomdemo.security;

import com.ecomdemo.customer.User;
import com.ecomdemo.customer.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * "Who is calling?", answered in one place.
 *
 * <p>The authenticated principal lives in the {@code SecurityContextHolder}, which is a
 * {@code ThreadLocal}: the filter chain puts it there at the start of the request and clears it
 * at the end, so any code running on that request's thread can read it without it being threaded
 * through every method signature. That is convenient and it is also the catch — a background
 * thread, an {@code @Async} method or a scheduled job has a different thread and therefore an
 * empty context. Everything in this application that calls into here runs on the request thread.
 *
 * <p>Wrapping the static holder in a bean is what makes the services that use it testable: a
 * unit test injects a stub of this class instead of having to populate a thread-local.
 *
 * <p><strong>Since Phase 9 the principal is a {@link Jwt}.</strong> Under HTTP Basic it was an
 * {@link AppUserDetails} built from a database row read on that very request; now it is the
 * verified token, and the id and username are claims inside it. Nothing here reads the database
 * to find out who is calling any more — that is precisely what a stateless token buys, and this
 * class is where the difference is visible.
 *
 * <p>{@link #require()} still reads the row, but for a different reason: the services that call
 * it — the cart, checkout — need a <em>managed</em> entity inside their own transaction so that
 * JPA associations point at real, attached instances. The id comes from the token, so this is a
 * primary-key lookup that Hibernate will usually serve from the first-level cache of the
 * transaction that already touched it.
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The id of the authenticated account, straight out of the token.
     *
     * <p>JSON has one number type, so a claim written as a {@code Long} comes back as whatever
     * the JSON parser chose — an {@code Integer} for small values, a {@code Long} for large
     * ones. Reading it as {@link Number} and narrowing is the difference between working
     * everywhere and working until the user table passes two billion rows.
     */
    public Long id() {
        Object claim = token().getClaim(JwtConfig.Claims.USER_ID);
        if (claim instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException(
                "The token carries no usable '%s' claim. It was issued by something other than "
                                .formatted(JwtConfig.Claims.USER_ID)
                        + "this application's login endpoint.");
    }

    /** The username of the authenticated account: the token's {@code sub} claim. */
    public String username() {
        return token().getSubject();
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
     * The verified token, or a failure.
     *
     * <p>Reaching here without an authenticated user is a bug in the security rules, not a
     * client error: every path that leads here is behind an {@code authenticated()} rule in
     * {@link SecurityConfig}, so the filter chain should already have answered 401. Failing
     * loudly makes a hole in those rules show up as a 500 in the tests rather than as a
     * {@code NullPointerException} three frames deeper.
     *
     * <p>The token reached here only by passing the {@code JwtDecoder}, so its signature, expiry
     * and issuer are already checked. Nothing below re-verifies anything, and nothing below
     * should: a claim on an unverified token is just a string somebody sent.
     */
    private Jwt token() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException(
                    "No authenticated user on this thread. A protected endpoint was reached "
                            + "without a Bearer token, or this ran off the request thread.");
        }
        return jwt;
    }
}

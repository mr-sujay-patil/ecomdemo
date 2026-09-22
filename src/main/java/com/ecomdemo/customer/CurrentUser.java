package com.ecomdemo.customer;

import com.ecomdemo.common.TokenClaims;
import com.ecomdemo.security.AppUserDetails;
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
 * <h2>Why this lives in {@code customer} and not in {@code security} (Phase 19)</h2>
 *
 * <p>It used to live in {@code security}, and that created the application's only dependency
 * CYCLE: {@code security} needs {@code User} and {@code UserRepository} to load an account, while
 * {@code customer} needed this class to find out who was calling. Two modules that each require
 * the other cannot be reasoned about separately, cannot be tested separately, and cannot be
 * extracted separately — which matters directly to Phase 20.
 *
 * <p>Moving one class broke it, and the move is right on its own merits rather than merely
 * convenient. The question this class answers — "which {@code User} is acting?" — is a question
 * about the customer domain; reading the {@code SecurityContextHolder} is just how it is
 * answered. It still uses Spring Security the LIBRARY, which is infrastructure any module may
 * use, but it no longer depends on this application's {@code security} module.
 *
 * <p>The cycle was not the only thing that went. {@code cart} and {@code order} imported nothing
 * else from {@code security}, so their dependency on it disappeared with this file: three edges
 * removed by moving one class. {@code ModularityTest} is what keeps it that way.
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
        Object claim = token().getClaim(TokenClaims.USER_ID);
        if (claim instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException(
                "The token carries no usable '%s' claim. It was issued by something other than "
                                .formatted(TokenClaims.USER_ID)
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

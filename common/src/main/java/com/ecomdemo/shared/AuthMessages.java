package com.ecomdemo.shared;

/**
 * The wording of the authentication failures, shared by the two places that produce them.
 *
 * <p>A caller who presents no token can be refused from either side of the filter chain: by the
 * {@code AuthenticationEntryPoint}, when the chain itself rejects the request, or by
 * {@code GlobalExceptionHandler}, when method security denies an anonymous caller further in.
 * Those are different code paths answering the same question, and a shopper should not be able to
 * tell which one answered.
 *
 * <p><strong>It lives in {@code shared} since Phase 20, and the reason is structural.</strong> The
 * constant used to sit on {@code ApiErrorAuthenticationEntryPoint} in the {@code security} module,
 * so the handler in {@code shared} imported it — which made {@code shared} depend on
 * {@code security}. That was survivable while everything was one deployable and fatal the moment
 * {@code shared} became a library that {@code security} is built on top of: Maven cannot resolve a
 * cycle between two modules.
 *
 * <p>The fix is the same one Phase 19 applied to the JWT claim names. A string that two modules
 * must agree on is a contract, and a contract belongs to neither of them — it belongs to the
 * shared kernel, which is what a shared kernel is for. A constant is the cheapest possible form of
 * it: no behaviour, no state, and if the wording changes both sides recompile against it.
 */
public final class AuthMessages {

    /** Sent when no token was presented at all. */
    public static final String NO_TOKEN =
            "Authentication required. Log in at POST /api/auth/login and send the token as "
                    + "'Authorization: Bearer <token>'.";

    /**
     * Sent when a token was presented but could not be trusted.
     *
     * <p>The wording is carried over UNCHANGED from where this constant used to live. Moving a
     * constant is a refactor; rewording it is a change to what a caller sees, and doing both in
     * one commit is how a user-facing change gets made by accident. {@code AuthApiIT} asserts on
     * this text and caught exactly that.
     */
    public static final String INVALID_TOKEN =
            "The token is invalid or has expired. Log in again at POST /api/auth/login.";

    private AuthMessages() {
    }
}

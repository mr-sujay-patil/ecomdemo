package com.ecomdemo.shared;

import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * The claim names this application writes into its tokens and reads back out.
 *
 * <p><strong>Why these constants are shared rather than owned by {@code security} (Phase 19).</strong>
 * A claim name is a contract with exactly two sides, and in this application the two sides are in
 * different modules: {@code auth} WRITES {@code uid} when it issues a token, and {@code customer}
 * READS it to find out who is calling. {@code security} configures the decoder that validates the
 * whole thing. Three modules, one agreement.
 *
 * <p>They used to live in {@code JwtConfig.Claims}, which made every reader depend on the
 * {@code security} module — and that was one half of the application's only dependency cycle,
 * because {@code security} in turn needs {@code User} and {@code UserRepository} from
 * {@code customer}. Moving the constants here, alongside moving {@code CurrentUser} into
 * {@code customer}, is what broke it.
 *
 * <p>This is what a shared module is actually for: not a drawer for things with no obvious home,
 * but the small set of agreements that several modules genuinely have to share. A constant is the
 * cheapest possible form of that — no behaviour, no state, nothing to couple to beyond the string
 * itself. If a claim name changes here, every module that cares fails to compile, which is
 * precisely the coupling that should exist.
 */
public final class TokenClaims {

    /** The account's database id. Saves a lookup by username on every request. */
    public static final String USER_ID = "uid";

    /** The roles, without the {@code ROLE_} prefix Spring Security adds back on the way in. */
    public static final String ROLES = "roles";

    /** The username. Standard: {@link JwtClaimNames#SUB}. */
    public static final String SUBJECT = JwtClaimNames.SUB;

    private TokenClaims() {
    }
}

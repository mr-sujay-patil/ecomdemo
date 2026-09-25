package com.ecomdemo.support;

import com.ecomdemo.jwt.JwtAuthorities;
import com.ecomdemo.shared.TokenClaims;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Puts a caller on the current thread, as a token rather than as an account.
 *
 * <p><strong>{@code account(UserRepository, ...)} is gone</strong>, and its removal is Phase 20d in
 * this one class. It used to find-or-create a row in {@code users} so that a test had a real account
 * to be. This application has no {@code users} table any more — customer-service owns it — so there is
 * nothing to create and no repository to create it with.
 *
 * <p>What is left is what was always doing the real work: building a {@link Jwt} and putting it in the
 * {@code SecurityContextHolder}. That was already the mechanism; the account was scaffolding around
 * it. The services under test read {@code uid} and the subject from the token, so a test that supplies
 * those has supplied everything they can see.
 *
 * <p><strong>Why {@code @WithMockUser} is still not enough</strong> (the reason this class has existed
 * since Phase 8): {@code @WithMockUser} installs a {@code UsernamePasswordAuthenticationToken} whose
 * principal is a string. {@code CurrentUser} needs a {@code Jwt} to read {@code uid} from, and would
 * throw. The claims are the interface now, so the test has to speak in claims.
 */
public final class TestAuthentication {

    /** The id a test gets when it does not care which account it is. */
    public static final long DEFAULT_USER_ID = 1L;

    private TestAuthentication() {
    }

    /** Authenticates as a CUSTOMER with the given id and username. */
    public static void authenticateAs(long userId, String username) {
        authenticateAs(userId, username, "CUSTOMER");
    }

    /**
     * Authenticates as whoever the arguments say.
     *
     * <p>The role is a plain string because that is what the {@code roles} claim carries. There is no
     * {@code Role} enum on this side of the split — it belongs to the service that owns accounts, and
     * duplicating it here would make two definitions of one contract.
     */
    public static void authenticateAs(long userId, String username, String role) {
        Instant issuedAt = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token-not-signed")
                .header("alg", "none")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(15, ChronoUnit.MINUTES))
                .subject(username)
                .claim(TokenClaims.USER_ID, userId)
                .claim(TokenClaims.ROLES, List.of(role))
                .build();

        // The authorities are built the same way the real converter builds them, so a @PreAuthorize
        // expression behaves in a test exactly as it does in production. Using the shared prefix
        // rather than a literal "ROLE_" is what keeps that true if the convention ever changes.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(JwtAuthorities.ROLE_PREFIX + role)), username));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}

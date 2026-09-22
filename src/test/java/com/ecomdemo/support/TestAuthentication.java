package com.ecomdemo.support;

import com.ecomdemo.shared.TokenClaims;
import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.User;
import com.ecomdemo.customer.UserRepository;
import com.ecomdemo.security.AppUserDetails;
import com.ecomdemo.security.JwtConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Signs a {@code @SpringBootTest} in as a real account.
 *
 * <p>{@code @WithMockUser} is the usual way to do this and is the right tool in the
 * {@code @WebMvcTest} slices, but it cannot be used here. It puts a Spring Security
 * {@code org.springframework.security.core.userdetails.User} in the context, and since Phase 9
 * {@code CurrentUser} reads a {@link Jwt} — the account's id and username are <em>claims</em>
 * now, not fields on a {@code UserDetails}. A fabricated principal of the wrong type fails on
 * the first call, and one naming an account that does not exist fails on the first
 * {@code currentUser.require()}.
 *
 * <p>So these tests build the same principal the filter chain would: a {@link Jwt} carrying the
 * claims {@link com.ecomdemo.auth.TokenService} writes, for a row that was actually persisted.
 * That keeps the token, the {@code users} row and the cart's {@code user_id} genuinely in
 * agreement.
 *
 * <p>The token here is <strong>not signed</strong>, and does not need to be: it is installed
 * directly into the {@code SecurityContext}, downstream of the decoder that would have verified
 * it. These tests call services, not HTTP. Signature verification is exercised where it belongs
 * — over real HTTP in the {@code *ApiIT} classes, which log in for a genuine token.
 */
public final class TestAuthentication {

    private TestAuthentication() {
    }

    /**
     * Finds or creates an account. Every {@code @SpringBootTest} in the suite shares one H2
     * database, so a class that ran earlier may already have created this username; the unique
     * constraint would refuse a second insert.
     */
    public static User account(UserRepository userRepository, String username, Role role) {
        return userRepository
                .findByUsername(username)
                .orElseGet(() -> userRepository.save(
                        new User(username, "{not-a-real-hash}", username, role, Instant.now())));
    }

    /**
     * Puts that account into the {@code SecurityContext} of the CALLING thread.
     *
     * <p>The thread matters. {@code SecurityContextHolder} is a {@code ThreadLocal}, so a thread
     * this test starts itself — the two racing checkouts in {@code ConcurrentCheckoutTest} —
     * inherits nothing and has to call this for itself. That is not a quirk of the test: it is
     * the same reason an {@code @Async} method or a scheduled job in production would find an
     * empty context.
     */
    public static void authenticateAs(User user) {
        Instant issuedAt = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token-not-signed")
                .header("alg", "none")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(15, ChronoUnit.MINUTES))
                .subject(user.getUsername())
                .claim(TokenClaims.USER_ID, user.getId())
                .claim(TokenClaims.ROLES, List.of(user.getRole().name()))
                .build();

        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(
                        jwt,
                        List.of(new SimpleGrantedAuthority(AppUserDetails.ROLE_PREFIX + user.getRole().name())),
                        user.getUsername()));
    }

    /** Clears the context, so an authenticated thread cannot leak into the next test. */
    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}

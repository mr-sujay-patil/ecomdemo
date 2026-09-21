package com.ecomdemo.support;

import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.User;
import com.ecomdemo.customer.UserRepository;
import com.ecomdemo.security.AppUserDetails;
import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Signs a {@code @SpringBootTest} in as a real account.
 *
 * <p>{@code @WithMockUser} is the usual way to do this and is the right tool in the
 * {@code @WebMvcTest} slices, but it cannot be used here. It puts a Spring Security
 * {@code org.springframework.security.core.userdetails.User} in the context, and this
 * application's {@code CurrentUser} insists on an {@link AppUserDetails} because it needs the
 * account's database id — the cart and the orders are keyed by it. A fabricated principal would
 * also name an account that does not exist, and the very first {@code currentUser.require()}
 * would fail looking it up.
 *
 * <p>So these tests authenticate as a row they actually persisted. That is a little more setup
 * than an annotation, and it buys something worth having: the id in the principal, the row in
 * {@code users} and the {@code user_id} on the cart all genuinely agree, which is exactly what a
 * test that exercises per-user data has to get right.
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
        AppUserDetails details = new AppUserDetails(user);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        details, null, details.getAuthorities()));
    }

    /** Clears the context, so an authenticated thread cannot leak into the next test. */
    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}

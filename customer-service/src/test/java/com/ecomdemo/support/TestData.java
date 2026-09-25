package com.ecomdemo.support;

import com.ecomdemo.customer.Role;
import com.ecomdemo.customer.User;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for the accounts this service owns.
 *
 * <p>Split out of the application's {@code TestData} in Phase 20d, which built users, products, carts
 * and orders. Only the account half is here, and it is the only half customer-service can construct.
 */
public final class TestData {

    private TestData() {
    }

    /**
     * A user with a fixed id and a fixed hash.
     *
     * <p>The "hash" is not a real BCrypt hash and does not need to be. Nothing in a unit test verifies
     * a password: the encoder is either mocked or bypassed by {@code @WithMockUser}. A string that
     * could never be produced by BCrypt is better than a plausible one, because it cannot be mistaken
     * for a working credential if it ever escapes into a log.
     */
    public static User user(long id, String username, Role role) {
        User user = new User(username, "{not-a-real-hash}", username + " the " + role, role,
                Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    public static User customer() {
        return user(1L, "customer", Role.CUSTOMER);
    }

    public static User admin() {
        return user(2L, "admin", Role.ADMIN);
    }
}

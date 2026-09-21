package com.ecomdemo.customer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * The query behind every authenticated request: the filter chain reads the username out of
     * the Authorization header and this is what turns it into an account.
     *
     * <p>{@code uq_users_username} in V5 both guarantees the {@code Optional} holds at most one
     * row and gives the index that makes this lookup cheap enough to run on every call.
     */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}

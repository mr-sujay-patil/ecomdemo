package com.ecomdemo.security;

import com.ecomdemo.customer.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security's view of an account.
 *
 * <p>Spring Security never touches {@link User}. It works with {@link UserDetails}: a username,
 * the stored credential to compare against, and a set of authorities. This class is the adapter
 * between the two, and having it means the JPA entity stays free of framework interfaces and the
 * framework stays free of JPA — change either side and only this file has to follow.
 *
 * <p>It carries one thing Spring Security does not ask for: the {@link #id}. Everything the
 * application owns is keyed by user id — the cart, the orders — and pulling it off the
 * authenticated principal saves a lookup by username on every single request that touches them.
 *
 * <p>{@link #getPassword()} returns the BCrypt hash. That is what {@code UserDetails} means by
 * "password": the stored credential the {@code PasswordEncoder} compares a submitted password
 * against. Nothing ever decodes it.
 *
 * <p>The {@code isAccountNonExpired} family is left at {@code true} by the interface's defaults.
 * Account locking, expiry and forced password resets are real features with real schema behind
 * them; pretending to have them with hardcoded {@code true} is honest only as long as nobody
 * reads it as a promise.
 */
public class AppUserDetails implements UserDetails {

    /**
     * Spring Security's {@code hasRole("ADMIN")} is shorthand for
     * {@code hasAuthority("ROLE_ADMIN")}. The prefix is pure convention with no business
     * meaning, so it is added here and never written to the database.
     */
    public static final String ROLE_PREFIX = "ROLE_";

    private final Long id;
    private final String username;
    private final String password;
    private final List<GrantedAuthority> authorities;

    public AppUserDetails(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.authorities = List.of(new SimpleGrantedAuthority(ROLE_PREFIX + user.getRole().name()));
    }

    /** The account's database id, so the cart and the orders can be found without a second query. */
    public Long getId() {
        return id;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }
}

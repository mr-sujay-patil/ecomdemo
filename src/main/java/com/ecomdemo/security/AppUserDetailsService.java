package com.ecomdemo.security;

import com.ecomdemo.customer.UserDirectory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where Spring Security gets its accounts from.
 *
 * <p>This is the whole of the "look the user up" half of authentication. Spring Security's
 * {@code DaoAuthenticationProvider} calls {@link #loadUserByUsername} with whatever username
 * arrived in the request, gets a {@link UserDetails} back, and then asks the
 * {@code PasswordEncoder} whether the submitted password matches the stored hash. Two separate
 * responsibilities, two separate beans — which is why swapping the store (LDAP, an OAuth2
 * provider in Phase 9) does not touch the hashing, and vice versa.
 *
 * <p>Declaring a {@code UserDetailsService} bean also switches off Spring Boot's default
 * in-memory user, the one that prints a random UUID password to the console at startup. That
 * generated user exists only so that a brand-new application is not wide open; a real user store
 * replaces it.
 *
 * <p><strong>Why the exception says so little.</strong> "User not found" and "wrong password"
 * both end as the same 401 with the same body. Telling a caller which one it was hands them a
 * free username-enumeration oracle: they could probe for valid accounts without ever guessing a
 * password. The detail below is for the application's own logs, not for the client — Spring
 * Security translates it into a {@code BadCredentialsException} before anything is sent back.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserDirectory users;

    public AppUserDetailsService(UserDirectory users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(AppUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No account named " + username));
    }
}

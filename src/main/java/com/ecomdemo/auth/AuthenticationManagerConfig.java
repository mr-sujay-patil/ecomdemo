package com.ecomdemo.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password check, still done the Phase 8 way — but now only at {@code /api/auth/login}.
 *
 * <p>A {@code DaoAuthenticationProvider} pairs the {@code UserDetailsService} with the
 * {@code PasswordEncoder}: look the username up, then ask the encoder whether the submitted
 * password matches the stored hash. That is exactly what {@code httpBasic()} was assembling
 * behind the scenes before this phase; what changed is that it now runs once per login instead
 * of once per request.
 *
 * <p>Spring Boot contributes an {@code AuthenticationManager} automatically only when an
 * application has no {@code SecurityFilterChain} of its own. This one does, so the manager has
 * to be declared.
 *
 * <p>It lives here, beside the endpoint that uses it, rather than in {@code SecurityConfig} —
 * and that placement is load-bearing for the tests. The {@code @WebMvcTest} slices import
 * {@code SecurityConfig} to get the real rules, and a slice has no {@code UserDetailsService}
 * bean; keeping the manager out of that class is what lets the slices build a context at all.
 * It is also the more honest split: {@code SecurityConfig} is about what the filter chain does
 * to every request, and this is about one endpoint.
 */
@Configuration
public class AuthenticationManagerConfig {

    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}

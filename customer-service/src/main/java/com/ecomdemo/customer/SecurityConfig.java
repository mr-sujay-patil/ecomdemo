package com.ecomdemo.customer;

import com.ecomdemo.jwt.ApiErrorAccessDeniedHandler;
import com.ecomdemo.jwt.ApiErrorAuthenticationEntryPoint;
import com.ecomdemo.jwt.JwtAuthorities;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call customer-service.
 *
 * <p><strong>It is the one extracted service with genuinely public paths</strong>, and that makes it
 * different from catalog-service and inventory-service in a way worth stating. Those two require a
 * token everywhere, because their only callers are other services. This one has two endpoints that
 * cannot possibly require a token:
 *
 * <ul>
 *   <li><strong>login</strong> — it exists to hand out the token, so requiring one is a circle;
 *   <li><strong>register</strong> — a person with no account cannot authenticate as one.
 * </ul>
 *
 * <p>Those two are open here for the same reason they are open on the application's edge, and this is
 * the only service where "open" is about a human rather than about who happens to be calling.
 * Everything else needs a valid token, and a profile request is answered for whoever the token says
 * it is — never for an id in the URL, which is why {@code /api/customers/me} has no id in it.
 *
 * <p><strong>The password encoder lives here</strong>, and it is the last thing to leave the
 * application. Hashing and comparing a password is this service's exclusive job now; no other service
 * has a {@code PasswordEncoder} bean, an {@code AuthenticationManager} or a {@code UserDetailsService},
 * and none of them can check a credential even if asked to.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ApiErrorAuthenticationEntryPoint entryPoint,
            ApiErrorAccessDeniedHandler accessDenied) throws Exception {
        return http
                // No cookie, no session, so nothing for a cross-site request to ride on. Disabled for
                // the same reason as in every other service, not as a shortcut.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The two that cannot require a token. Both are POST-only: a GET on either
                        // would be a different operation and should not inherit this permission.
                        .requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // Compose's healthcheck has no credentials, and neither has the Prometheus
                        // scraper. EndpointRequest rather than a literal path, because the base path
                        // is configurable and a literal stops matching when it moves.
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // The safety net: anything added later is closed until someone opens it
                        // deliberately. A forgotten endpoint answers 401, which is a bug report
                        // rather than a breach.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthorities.converter()))
                        // THE RESOURCE SERVER HAS AN ENTRY POINT OF ITS OWN, and it is the one used
                        // when a token is PRESENT but bad - expired, tampered with, or not a JWT at
                        // all. The `exceptionHandling` block below covers the other case, where there
                        // is no token and the denial comes from the authorization rules.
                        //
                        // Both paths have to be wired, and it is easy to get half right: without this
                        // line a tampered token comes back with an EMPTY body and a
                        // `WWW-Authenticate: Bearer error="invalid_token"` header instead of the
                        // ApiError shape every other failure uses. The application's chain carries a
                        // comment saying exactly that, written in Phase 9 - and this service still
                        // reproduced the bug, because a comment on one class does not configure
                        // another. AuthApiIT caught it the first time it ran.
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                // The same ApiError shape for 401 and 403 that every other service produces, from the
                // handlers that moved into `common` with this split - so a client cannot tell which
                // service refused it from the shape of the refusal.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .cors(Customizer.withDefaults())
                .build();
    }

    /**
     * How passwords are hashed, and now the only place in the system that decides it.
     *
     * <p>BCrypt with the default strength of 10. It is deliberately SLOW — that is the feature, not a
     * cost to optimise away: a hash an attacker can compute a billion times a second is a hash worth
     * nothing. Each hash also carries its own salt, so two accounts with the same password get
     * different hashes and a stolen table cannot be attacked with one rainbow table.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

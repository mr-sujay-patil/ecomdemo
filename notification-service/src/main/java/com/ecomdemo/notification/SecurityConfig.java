package com.ecomdemo.notification;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call notification-service: essentially nobody.
 *
 * <p><strong>This file exists because of Phase 20b, and it is the cheapest lesson in the project.</strong>
 * The pom carries the security starter, and Boot's fallback when nothing configures a chain is to secure
 * every path with HTTP Basic and a generated password. The service would start, report itself HEALTHY —
 * the probe being the one path that fallback leaves open — and compose would be satisfied. It took a
 * failing smoke test to find that in 20b. Here it is written on the first day.
 *
 * <p><strong>It has no business API at all</strong>, which makes this the shortest chain in the system
 * and the only one where {@code anyRequest().denyAll()} is the honest rule rather than a mistake. This
 * service reacts to a topic; there is nothing to call. If an endpoint ever appears here, {@code denyAll}
 * makes it 403 until somebody decides otherwise — which is the right default for a service that is not
 * supposed to have one.
 *
 * <p>What is open is what has to be: the readiness probe compose waits on, and the metrics endpoint
 * Prometheus scrapes. Neither has credentials.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers("/error").permitAll()

                        // Not `authenticated()`. There is no caller this service should admit, so a
                        // request arriving at any other path is either a mistake or a probe, and both
                        // deserve the same answer.
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}

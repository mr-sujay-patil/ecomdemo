package com.ecomdemo.payment;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * No business API, so nothing but the probes and the metrics is reachable - notification-service's
 * rule. A payment is started by an EVENT, never by a request, so there is no endpoint through
 * which anyone could ask this service to charge anything.
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
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}

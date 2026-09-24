package com.ecomdemo.inventory;

import com.ecomdemo.jwt.JwtAuthorities;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call inventory-service.
 *
 * <p><strong>Why this file exists at all.</strong> The pom declares the resource-server starter, and
 * Spring Boot's fallback when nothing configures a chain is to secure every path with HTTP Basic
 * and a password it prints to the log. So the service was not unprotected without this class — it
 * was protected by a credential nobody had, which is how the first compose run of Phase 20b ended:
 * every call from the application came back {@code 401} and the product listing turned it into a
 * {@code 500}.
 *
 * <p><strong>Authentication, not authorisation.</strong> Every business endpoint requires a valid
 * token and nothing more. That is a deliberate and load-bearing limitation: the application calls
 * this service with its <em>own</em> identity (see {@code ServiceTokens}), so the token arriving
 * here says {@code ecomdemo-app}, never {@code alice}, and this service genuinely cannot tell an
 * administrator's stock edit from a shopper's. Deciding that only an administrator may set a stock
 * level stays where the human's token is — at the edge, in the service that owns the endpoint they
 * called.
 *
 * <p>That arrangement is safe only while this service is unreachable from outside the compose
 * network. Its port is published for the smoke test, which makes "unreachable" a claim about a
 * laptop rather than a control. Recorded as a known gap in {@code docs/decisions.md}; the fix is a
 * gateway in front and no published port, which is a later phase.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF protects a session cookie the browser attaches automatically. There is no
                // cookie and no session here: every request carries a bearer token that an
                // attacker's page cannot read or set. Disabled for the same reason as in the
                // application, not as a shortcut.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The liveness and readiness probes, which compose's healthcheck calls
                        // with no credentials, and the Prometheus scrape. `EndpointRequest` is used
                        // rather than a literal "/actuator/**" because the base path is
                        // configurable and a literal silently stops matching when it moves.
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthorities.converter())))
                // No form login and no HTTP Basic: a service API has no login page, and leaving
                // Basic enabled would mean a second way in that this file says nothing about.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .cors(Customizer.withDefaults())
                .build();
    }
}

package com.ecomdemo.inventory;

import com.ecomdemo.jwt.ApiErrorAccessDeniedHandler;
import com.ecomdemo.jwt.ApiErrorAuthenticationEntryPoint;
import com.ecomdemo.jwt.JwtAuthorities;
import com.ecomdemo.jwt.ServiceTokens;
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
 * <p><strong>Authorisation, not only authentication (Phase 31).</strong> Until the security review
 * this chain required a valid token and nothing more, leaving "only an administrator may set a stock
 * level" to the edge, and the note here admitted that was safe only while the port was unreachable.
 * It is published in compose, and the review proved the gap on the running stack: a CUSTOMER's
 * token sent straight to {@code PUT /api/inventory/{id}} was accepted (docs/security.md, API5).
 *
 * <p>The fix needs no new information, because no shopper ever has a reason to call this service:
 * the gateway refuses {@code /api/inventory/**} to anyone but an ADMIN, and every other caller
 * (the application's checkout, catalog's stock lookups) sends its own SERVICE token. So every
 * business path now requires ADMIN or SERVICE, and a CUSTOMER gets 403 here as well as at the edge.
 * {@code InventorySecurityTest} proves it.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ApiErrorAuthenticationEntryPoint entryPoint,
            ApiErrorAccessDeniedHandler accessDenied) throws Exception {
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
                        .anyRequest().hasAnyRole("ADMIN", ServiceTokens.ROLE))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthorities.converter()))
                        // The resource server's own entry point, for a token that is present but bad.
                        // Without it, a tampered or expired token gets an empty body instead of the
                        // ApiError shape - so a caller cannot tell a rejected token from a network
                        // failure. See the longer note in customer-service's chain.
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                // No form login and no HTTP Basic: a service API has no login page, and leaving
                // Basic enabled would mean a second way in that this file says nothing about.
                // The same ApiError shape for a missing token that every other service produces.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .cors(Customizer.withDefaults())
                .build();
    }
}

package com.ecomdemo.catalog;

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
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call catalog-service.
 *
 * <p>Written from inventory-service's chain rather than discovered the hard way, which is the point
 * of having paid for that lesson once. Phase 20b extracted a service with the resource-server
 * starter on its classpath and no chain of its own; Boot's fallback then secured every path with a
 * generated password nobody had, the service reported itself <strong>healthy</strong> because the
 * health probe is the one path that fallback leaves open, and every call came back {@code 401}.
 * {@code CatalogSecurityTest} is this service's copy of the test that found it.
 *
 * <p><strong>Authentication, not authorisation</strong>, and the reasoning is the same as
 * inventory's. The application calls this service with its <em>own</em> identity, so the token
 * arriving here says {@code ecomdemo-app}, never {@code alice}. This service cannot tell an
 * administrator creating a product from a shopper reading one. "Only an ADMIN may create a product"
 * therefore stays at the edge, on the application's own {@code /api/products} — which is also where
 * the human's token actually is.
 *
 * <p>Note what that means for reads: the public product listing is public on the APPLICATION, not
 * here. Every path on this service needs a token, including the GETs, because the only caller is
 * another service and "open because of who happens to call it" is not a boundary.
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
                        .anyRequest().authenticated())
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

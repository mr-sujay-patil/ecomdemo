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
 * (the application's checkout, catalog's stock lookups) sends its own service token. So every
 * business path requires ADMIN or a service, and a CUSTOMER gets 403 here as well as at the edge.
 *
 * <p><strong>Scoped since Phase 33.</strong> "A service" used to mean the one shared SERVICE role,
 * so catalog-service, which only ever reads stock, could equally set it. Now reads need ADMIN or
 * {@code inventory:read} (catalog-service, the application) and every change needs ADMIN or
 * {@code inventory:write} (only the application: checkout, the saga, the stock import).
 * {@code InventorySecurityTest} proves both, and that a read-only token cannot write.
 */
@Configuration
public class SecurityConfig {

    private static final String ADMIN = JwtAuthorities.ROLE_PREFIX + "ADMIN";
    private static final String READ = ServiceTokens.authority(ServiceTokens.INVENTORY_READ);
    private static final String WRITE = ServiceTokens.authority(ServiceTokens.INVENTORY_WRITE);

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
                        // KI-001: the OpenAPI document, which the gateway serves at /v3/api-docs/inventory. Open,
                        // because it describes the API's shape, not its data, and a reader needs it to
                        // learn how to authenticate. GET only, and only the JSON document.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/**").hasAnyAuthority(ADMIN, READ)
                        .anyRequest().hasAnyAuthority(ADMIN, WRITE))
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

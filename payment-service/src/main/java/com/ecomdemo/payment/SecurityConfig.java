package com.ecomdemo.payment;

import com.ecomdemo.jwt.ApiErrorAccessDeniedHandler;
import com.ecomdemo.jwt.ApiErrorAuthenticationEntryPoint;
import com.ecomdemo.jwt.JwtAuthorities;
import com.ecomdemo.jwt.ServiceTokens;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * No business API, so nothing but the probes and the metrics is reachable - notification-service's
 * rule. A payment is started by an EVENT, never by a request, so there is no endpoint through
 * which anyone could ask this service to charge anything.
 *
 * <h2>Two chains since Phase 32</h2>
 *
 * <p>The saga deadline added one internal question, {@code POST /internal/saga/orders/{id}/settle}
 * ({@link SettlementController}). Since Phase 33 only a token with the {@code payment:settle} scope may
 * ask it, which customer-service gives to the application alone: the gateway or catalog-service,
 * which held the same SERVICE role until then, now get 403. It gets a chain of its own rather
 * than a line in the existing one, so the existing one stays exactly what it was: no
 * authentication mechanism at all, and everything outside the probes refused with 403 - including
 * anything that looks like a payment API under {@code /api}. A single chain with a resource server
 * would have turned those 403s into 401s ("log in and try again"), which says the wrong thing.
 */
@Configuration
public class SecurityConfig {

    /** {@code /internal/**}: service-to-service only, with a JWT, like inventory-service's chain. */
    @Bean
    @Order(1)
    SecurityFilterChain internalChain(
            HttpSecurity http,
            ApiErrorAuthenticationEntryPoint entryPoint,
            ApiErrorAccessDeniedHandler accessDenied) throws Exception {
        return http
                .securityMatcher("/internal/**")
                // No cookie and no session: every call carries a bearer token.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest()
                        .hasAuthority(ServiceTokens.authority(ServiceTokens.PAYMENT_SETTLE)))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthorities.converter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }

    @Bean
    @Order(2)
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

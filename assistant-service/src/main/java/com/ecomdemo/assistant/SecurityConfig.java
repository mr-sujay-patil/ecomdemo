package com.ecomdemo.assistant;

import com.ecomdemo.jwt.ApiErrorAccessDeniedHandler;
import com.ecomdemo.jwt.ApiErrorAuthenticationEntryPoint;
import com.ecomdemo.jwt.JwtAuthorities;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Only customers talk to the assistant, and the rule is here as well as at the gateway.
 *
 * <p>The gateway's rule is the one a customer meets. This one is what stops anything else inside
 * the network - which reaches this service without passing the gateway - from using it. It
 * matters more here than in most services: every tool the assistant calls reads the CUSTOMER's
 * cart or orders with the caller's own token, and a service token has no cart and no orders.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ApiErrorAuthenticationEntryPoint entryPoint,
            ApiErrorAccessDeniedHandler accessDenied) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        // KI-001: the OpenAPI document, which the gateway serves at /v3/api-docs/assistant. Open,
                        // because it describes the API's shape, not its data, and a reader needs it to
                        // learn how to authenticate. GET only, and only the JSON document.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs").permitAll()
                        .requestMatchers("/api/assistant/**").hasRole("CUSTOMER")
                        .anyRequest().denyAll())
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
}

package com.ecomdemo.gateway;

import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Who may reach what, decided once, at the edge.
 *
 * <p><strong>The rules below are a deliberate mirror, not a replacement.</strong> Every one of them
 * also exists inside the service that owns the endpoint, and both copies stay. The gateway is the
 * convenient place to say "anonymous may read the catalogue"; it is not a safe place to say it
 * <em>only</em>, because then a single mistyped route — or a request that reaches a service by
 * another path, as every integration test does — would meet no rule at all. Phase 20d removed
 * {@code @PreAuthorize} from inventory-service and recorded that "authorisation stays at the edge";
 * this phase is where that edge finally exists, and the services keep their chains anyway.
 *
 * <p><strong>{@code anyRequest().authenticated()} is the safety net, and it is the last line for a
 * reason.</strong> A route added later without a rule of its own is answered 401 rather than served.
 * The failure mode of forgetting is then a rejected request, not an open door.
 *
 * <p><strong>CSRF is disabled, as it is in every service.</strong> There is no session and no cookie
 * to ride on: the token travels in an {@code Authorization} header that a browser will not attach on
 * a cross-site request by itself. CSRF protection defends a credential the browser sends
 * automatically, which is exactly what this system does not have.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ApiErrors apiErrors) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // --- the two anonymous doors, and nothing more ---
                        // Login and registration cannot require a token; that is what they are for.
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/customers/register").permitAll()

                        // Reading the catalogue is anonymous - a shop nobody can browse sells
                        // nothing - but WRITING it is an administrator's job. The order matters:
                        // the GET rule is narrower and must come first.
                        .pathMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .pathMatchers("/api/products/**").hasRole("ADMIN")

                        // Stock is not a public resource. It was reachable only in-network until
                        // this phase; exposing it through the gateway is what makes an explicit
                        // ADMIN rule necessary rather than theoretical.
                        .pathMatchers("/api/inventory/**").hasRole("ADMIN")

                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/cart/**", "/api/orders/**").hasRole("CUSTOMER")

                        // The gateway's OWN health and metrics. Kubernetes and Prometheus have no
                        // token, and Phase 25 will probe this container like any other.
                        .matchers(EndpointRequest.to("health", "info")).permitAll()
                        .matchers(EndpointRequest.to("prometheus")).permitAll()
                        .matchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")

                        .anyExchange().authenticated())
                // THE RESOURCE SERVER NEEDS ITS OWN ENTRY POINT. This is the 20d defect, written
                // out rather than inherited: `exceptionHandling` below covers a request that
                // arrived with NO credentials, but a request whose TOKEN was rejected is handled
                // inside the resource server, which has an entry point of its own and defaults to
                // an empty body with a WWW-Authenticate header. Two services shipped that way for a
                // phase because one line looked like it covered both cases.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(apiErrors)
                        .accessDeniedHandler(apiErrors)
                        .jwt(jwt -> {
                        }))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(apiErrors)
                        .accessDeniedHandler(apiErrors))
                .build();
    }
}

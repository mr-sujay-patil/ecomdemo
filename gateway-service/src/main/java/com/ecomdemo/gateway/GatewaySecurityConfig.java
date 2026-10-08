package com.ecomdemo.gateway;

import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

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
 *
 * <p><strong>CORS is decided here, FIRST (KI-041).</strong> Before a cross-origin request with a
 * token or a JSON body, a browser sends a preflight: an {@code OPTIONS} with no token at all. Without
 * {@code .cors(...)} this chain met it before the gateway's CORS configuration did, and
 * {@code anyExchange().authenticated()} answered 401 - so no browser app on another origin could even
 * log in. With it, Spring Security's CORS filter runs before authorisation: it answers a preflight
 * itself (200 with the allow headers, or 403 for an origin that is not allowed) and adds the allow
 * header to every other response, a 401 included, so the browser can read why it was refused.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ApiErrors apiErrors) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // --- the two anonymous doors, and nothing more ---
                        // Login and registration cannot require a token; that is what they are for.
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/customers/register").permitAll()

                        // KI-001: the API documentation - Swagger UI, its assets and configuration,
                        // and each service's OpenAPI document. Open, because it describes the API's
                        // shape rather than its data, and a reader needs it to learn how to get a
                        // token. GET only: nothing here changes anything.
                        .pathMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/webjars/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()

                        // Reading the catalogue is anonymous - a shop nobody can browse sells
                        // nothing - but WRITING it is an administrator's job. The order matters:
                        // the GET rule is narrower and must come first.
                        //
                        // Phase 28: except the embedding backfill's status, which is under the same
                        // prefix and is operations data, not catalogue. So it goes FIRST of all:
                        // the first matching rule wins, and the public GET rule would match it too.
                        .pathMatchers("/api/products/embeddings/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .pathMatchers("/api/products/**").hasRole("ADMIN")

                        // Stock is not a public resource. It was reachable only in-network until
                        // this phase; exposing it through the gateway is what makes an explicit
                        // ADMIN rule necessary rather than theoretical.
                        .pathMatchers("/api/inventory/**").hasRole("ADMIN")

                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/cart/**", "/api/orders/**").hasRole("CUSTOMER")
                        // Phase 29. Customers only: everything the assistant can look up or propose is
                        // the caller's own cart and orders, and an administrator has neither.
                        .pathMatchers("/api/assistant/**").hasRole("CUSTOMER")

                        // The gateway's OWN health and metrics. Kubernetes and Prometheus have no
                        // token, and Phase 25 will probe this container like any other.
                        //
                        // KI-006: these matchers only ever match on the MANAGEMENT port
                        // (management.server.port), which nothing publishes. On the public port they
                        // match nothing, so /actuator/** falls through to `authenticated()` below.
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

    /**
     * The rules {@code .cors(...)} applies: the gateway's own {@code globalcors} configuration from
     * {@code application.yml}, not a second copy. One place says which origins, methods and headers
     * are allowed ({@code CORS_ALLOWED_ORIGINS}), so the security chain and the routes cannot drift
     * apart. The routes' own CORS processing then sees the header already set and adds no duplicate:
     * a response with two {@code Access-Control-Allow-Origin} headers is one a browser rejects.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GlobalCorsProperties globalCors) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.setCorsConfigurations(globalCors.getCorsConfigurations());
        return source;
    }
}

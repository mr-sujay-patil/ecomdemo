package com.ecomdemo.security;

import com.ecomdemo.common.TokenClaims;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what.
 *
 * <p><strong>The filter chain.</strong> Adding the security starter puts a single servlet
 * {@code Filter} in front of the whole application, and that filter delegates to an ordered
 * chain of small ones. Each does one job and hands the request on: work out who is calling
 * (since Phase 9, {@code BearerTokenAuthenticationFilter} reading the {@code Authorization}
 * header and verifying a JWT), put the result in the {@code SecurityContextHolder}, and finally
 * decide whether this caller may have this URL ({@code AuthorizationFilter}). A request that
 * fails is answered <em>by the chain</em> and never reaches the {@code DispatcherServlet} —
 * which is why 401 and 403 need the handlers in this package rather than
 * {@code GlobalExceptionHandler}.
 *
 * <p><strong>What changed in Phase 9.</strong> Only the first of those steps. HTTP Basic sent the
 * password on every request, so every request cost a database lookup and a deliberately slow
 * BCrypt verification. A Bearer token replaces both with a signature check over bytes the
 * request already carries: nothing is read, nothing is hashed, and the application holds no
 * session either. The authorization rules below are untouched — they were always decided from
 * authorities, and where those authorities came from was never their concern.
 *
 * <p><strong>The rules are ordered and the first match wins.</strong> That makes the sequence
 * below meaningful, not cosmetic: the public GET rule for products has to come before the ADMIN
 * rule for products, because {@code /api/products/**} would otherwise swallow the reads too.
 *
 * <p><strong>{@code anyRequest().authenticated()} is the safety net.</strong> A new endpoint
 * added in a later phase is protected until somebody deliberately opens it. The opposite default
 * — everything public except what is listed — fails silently the first time somebody forgets a
 * line, and nothing in a test suite notices that a URL is readable by the world.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ApiErrorAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiErrorAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            ApiErrorAuthenticationEntryPoint authenticationEntryPoint,
            ApiErrorAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * Suppressed after review, not ignored. {@code java:S4502} asks "make sure disabling CSRF
     * protection is safe here", and the answer is in the CSRF comment inside the method: this API
     * is stateless, sets no cookie, and reads a Bearer token from a header the client must attach
     * deliberately, so there is no ambient authority to forge.
     *
     * <p>It is suppressed HERE, in the code, rather than marked "accepted" in SonarQube's
     * database — because a decision that lives only in the server is lost the moment the server
     * is rebuilt, and is invisible in code review. The day this application authenticates with a
     * cookie, deleting this annotation is what makes the rule speak up again.
     *
     * <p>Note also the absence of {@code throws Exception}, which every Spring Security example
     * carries. It was needed while {@code HttpSecurity.build()} declared a checked
     * {@code Exception}; Spring Security 7 no longer does, so keeping it would declare a failure
     * that cannot happen and force callers to handle it. The compiler confirms it: the class
     * builds without it.
     */
    @SuppressWarnings("java:S4502")
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                // --- CSRF ------------------------------------------------------------------
                // Cross-Site Request Forgery is an attack on AMBIENT AUTHORITY: the browser
                // attaches a session cookie to any request aimed at that origin, including one
                // triggered by a form on evil.example.com, so the server sees a perfectly
                // authenticated request the user never meant to send. The defence is a token
                // the attacker's page cannot read and therefore cannot include.
                //
                // None of that applies here. This API keeps no session and sets no cookie; the
                // credentials arrive in an Authorization header that the client must attach
                // deliberately on every call. A cross-site form submission simply arrives with
                // no credentials at all and is answered 401. There is no ambient authority to
                // forge, so the token would protect nothing and would break every non-browser
                // client.
                //
                // Note what this reasoning depends on: statelessness. The day this application
                // authenticates with a cookie, CSRF protection has to come back on.
                .csrf(csrf -> csrf.disable())

                // --- Sessions --------------------------------------------------------------
                // STATELESS: never create an HttpSession and never look for one. Every request
                // proves itself from scratch, which since Phase 9 costs a signature check rather
                // than a database read and a BCrypt verification.
                //
                // Stateless vs session-based, plainly: a session keeps the truth on the SERVER
                // and hands the client an opaque id, so logging someone out is a matter of
                // deleting a row — but every instance then needs access to that store, and
                // scaling means sharing it. A token keeps the truth in the TOKEN, signed, so any
                // instance can verify it with no shared state at all. The cost is the mirror
                // image: nothing can be withdrawn before it expires, which is why the expiry is
                // short.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(requests -> requests
                        // Registration must be reachable by someone who has no account yet:
                        // requiring authentication to create an account is a closed loop. The
                        // same is true of logging in — you cannot present a token to get a token.
                        .requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // The catalogue is the shop window. Browsing needs no account; changing
                        // the catalogue is the shopkeeper's job. Order matters here — see above.
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers("/api/products/**").hasRole("ADMIN")

                        // Batch jobs are operations, not shopping. The whole prefix is ADMIN
                        // in one rule rather than per endpoint, so an endpoint added to
                        // BatchController later is protected the moment it exists rather than
                        // the moment somebody remembers to add a line here.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // A cart and an order belong to a shopper. An ADMIN is refused here on
                        // purpose: these endpoints act on "my" cart and "my" orders, and an
                        // administrator has neither. Nothing about being an admin implies being
                        // a customer, and quietly granting both is how a role system stops
                        // meaning anything.
                        .requestMatchers("/api/cart/**", "/api/orders/**").hasRole("CUSTOMER")

                        // Spring forwards an unhandled exception to /error, and that forward
                        // goes through this filter chain like any other dispatch. Left to
                        // `anyRequest().authenticated()` it is answered 401 — so a genuine 500 on
                        // a PUBLIC endpoint arrives at the client as "authentication required",
                        // which sends whoever is debugging it a long way in the wrong direction.
                        // That is not hypothetical: a cache serialization bug in Phase 13 turned
                        // every catalogue read into exactly that, and the 401 hid it.
                        //
                        // Permitting /error does not expose anything: it renders whatever the
                        // failed request produced, and every deliberate error in this application
                        // already goes through GlobalExceptionHandler instead.
                        // --- Actuator (Phase 15) ------------------------------------------
                        // Matched by endpoint ID, not by URL. `EndpointRequest.to("health")`
                        // keeps meaning the health endpoint even if management.endpoints.web
                        // .base-path moves it off /actuator, which a hand-written
                        // "/actuator/health" rule would not — it would silently match nothing
                        // and the rule below it would take over. Security rules that fail open
                        // when configuration moves are worth avoiding by construction.
                        //
                        // health and info are anonymous because their callers are machines with
                        // no credentials: the container's own HEALTHCHECK, and every
                        // orchestrator probe after it. The body is not a giveaway — show-details
                        // is `when-authorized`, so an anonymous caller sees {"status":"UP"} and
                        // nothing about which component is unhappy.
                        .requestMatchers(EndpointRequest.to("health", "info")).permitAll()

                        // The scrape endpoint is open to the compose network for the same
                        // reason: Prometheus authenticates with nothing, and giving it a
                        // 15-minute JWT would mean re-issuing one every 15 minutes forever.
                        //
                        // This is a deliberate, bounded trade-off and not a recommendation. The
                        // page carries no customer data — meter names, counts and latencies —
                        // but it does describe the system: every URI template, the pool sizes,
                        // the heap. In a real deployment the fix is not authentication, it is
                        // reachability: move the management endpoints to their own port with
                        // `management.server.port` and publish that port only on the internal
                        // network, so the question of who may scrape it never reaches Spring
                        // Security at all. That is deliberately out of this phase's scope, and
                        // docs/decisions.md records why.
                        .requestMatchers(EndpointRequest.to("prometheus")).permitAll()

                        // Everything else Actuator exposes — /actuator itself and
                        // /actuator/metrics — is for an operator, so it needs an operator. This
                        // rule comes LAST of the four so the three specific ones win, and it is
                        // written as "any endpoint" rather than a list so that an endpoint added
                        // to the exposure list later is closed by default rather than open.
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")

                        .requestMatchers("/error").permitAll()

                        // The API description and the UI that renders it stay open, because a
                        // closed description is of no use to a client trying to work out how to
                        // authenticate. It documents the shape of the API, not its data.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()

                        .anyRequest().authenticated())

                // --- Bearer tokens ---------------------------------------------------------
                // "Resource server" is the OAuth2 name for an API that accepts tokens. The filter
                // it adds takes `Authorization: Bearer <jwt>`, hands the token to the JwtDecoder
                // (signature, expiry and issuer — see JwtConfig), and converts the claims into an
                // Authentication.
                //
                // The payload is only Base64url, so a client can read its own claims; what it
                // cannot do is change them, because the signature covers them. That is the
                // difference from Basic, where the header WAS the password.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        // The resource server has an entry point of its own, and it is the one
                        // used when a token is present but bad. Without this line a tampered or
                        // expired token would come back with an empty body and a
                        // `WWW-Authenticate: Bearer error="invalid_token"` header instead of the
                        // ApiError shape every other failure uses.
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                // And this is the one used when there is no token at all, where the denial comes
                // from the authorization rules rather than from the token filter. Both paths have
                // to be covered, which is easy to get half right.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * Turns the {@code roles} claim into Spring Security authorities.
     *
     * <p>The default converter reads OAuth2's {@code scope}/{@code scp} claim and prefixes with
     * {@code SCOPE_}. This application issues its own tokens and thinks in roles, so it reads
     * {@code roles} and prefixes with {@code ROLE_} — which is what makes the untouched
     * {@code hasRole("ADMIN")} rules above keep working, and what lets the token itself stay
     * free of a framework convention.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenClaims.ROLES);
        authorities.setAuthorityPrefix(AppUserDetails.ROLE_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * How passwords are hashed, and the only place that decision is made.
     *
     * <p>One bean is used for both directions — {@code encode} at registration and
     * {@code matches} at login — so the two can never drift apart. {@code matches} does not
     * hash-and-compare-strings: it reads the algorithm, cost and salt back out of the stored
     * hash, re-hashes the submitted password with exactly those, and compares the results in
     * constant time so that the comparison itself leaks nothing through timing.
     *
     * <p>BCrypt's default cost of 10 means 2^10 rounds of key setup per verification. That is
     * the point: it is slow enough to make a stolen table expensive to brute-force and fast
     * enough that a login is imperceptible. The cost is stored inside each hash, so raising it
     * later applies to new passwords without invalidating old ones.
     *
     * <p>{@code PasswordEncoder} is the interface rather than {@code BCryptPasswordEncoder}
     * because moving to Argon2, or to {@code DelegatingPasswordEncoder} for a gradual migration,
     * should be a change to this one line.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

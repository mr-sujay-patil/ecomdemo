package com.ecomdemo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what.
 *
 * <p><strong>The filter chain.</strong> Adding the security starter puts a single servlet
 * {@code Filter} in front of the whole application, and that filter delegates to an ordered
 * chain of small ones. Each does one job and hands the request on: work out who is calling
 * (here, {@code BasicAuthenticationFilter} reading the {@code Authorization} header), put the
 * result in the {@code SecurityContextHolder}, and finally decide whether this caller may have
 * this URL ({@code AuthorizationFilter}). A request that fails is answered <em>by the chain</em>
 * and never reaches the {@code DispatcherServlet} — which is why 401 and 403 need the handlers
 * in this package rather than {@code GlobalExceptionHandler}.
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                // carries its own credentials and is authenticated from scratch. That costs a
                // BCrypt verification per call — deliberately expensive work — which is the
                // price of Basic authentication and one of the reasons Phase 9 replaces it with
                // a signed token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(requests -> requests
                        // Registration must be reachable by someone who has no account yet:
                        // requiring authentication to create an account is a closed loop.
                        .requestMatchers(HttpMethod.POST, "/api/customers/register").permitAll()

                        // The catalogue is the shop window. Browsing needs no account; changing
                        // the catalogue is the shopkeeper's job. Order matters here — see above.
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers("/api/products/**").hasRole("ADMIN")

                        // A cart and an order belong to a shopper. An ADMIN is refused here on
                        // purpose: these endpoints act on "my" cart and "my" orders, and an
                        // administrator has neither. Nothing about being an admin implies being
                        // a customer, and quietly granting both is how a role system stops
                        // meaning anything.
                        .requestMatchers("/api/cart/**", "/api/orders/**").hasRole("CUSTOMER")

                        // The API description and the UI that renders it stay open, because a
                        // closed description is of no use to a client trying to work out how to
                        // authenticate. It documents the shape of the API, not its data.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()

                        .anyRequest().authenticated())

                // HTTP Basic: the username and password, joined by a colon, Base64-encoded into
                // `Authorization: Basic ...`. Base64 is an ENCODING, not encryption — anybody who
                // can see the request can read the password, so Basic is only acceptable over
                // TLS, or, as here, on localhost in a learning project.
                .httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
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

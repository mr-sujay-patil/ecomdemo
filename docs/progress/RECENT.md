# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 09: JWT Authentication (tag: phase-09-complete, PR #9)
**What exists now:** The password is sent once. `POST /api/auth/login` returns a signed HS256 JWT
(15 min) carrying `sub`, `uid` and `roles`; every other call sends `Authorization: Bearer <token>`
and the OAuth2 Resource Server filter verifies signature, expiry and issuer. No session, no
per-request database read, no per-request BCrypt: measured 106 ms for a login against 14 ms for an
authenticated call. Every Phase 8 rule is unchanged — they were always decided from authorities.
220 tests (190 + 30), smoke test 125 checks. Schema unchanged at V6.
**Key code:** `auth/` is new — `AuthController` (`POST /api/auth/login`), `AuthService` (delegates
to the `AuthenticationManager`, then issues), `TokenService` (builds the claim set and signs),
`AuthenticationManagerConfig` (the `DaoAuthenticationProvider`, deliberately NOT in
`SecurityConfig`), `dto/LoginRequest` (masks the password in `toString`), `dto/TokenResponse`.
In `security/`: `JwtProperties` + `JwtConfig` (the `SecretKey`, `JwtEncoder`, `JwtDecoder` and
`Claims` constants), `SecurityConfig` swaps `httpBasic` for `oauth2ResourceServer` with a
`JwtGrantedAuthoritiesConverter` on the `roles` claim, `CurrentUser` now reads the `Jwt`
principal, `ApiErrorAuthenticationEntryPoint` distinguishes "no token" from "bad token".
`GlobalExceptionHandler` maps `AuthenticationException` to one identical 401.
**Config & infrastructure:** New dependency `spring-boot-starter-oauth2-resource-server` (brings
`spring-security-oauth2-jose`, so the encoder needs nothing extra). New properties
`ecomdemo.jwt.secret=${JWT_SECRET:}`, `ecomdemo.jwt.issuer=ecomdemo`, `ecomdemo.jwt.expiry=15m`.
No default key in Git: unset means a random key plus a loud WARN; under 32 bytes fails startup.
OpenAPI now declares `bearerAuth` (`bearerFormat: JWT`) and no longer declares `basicAuth`.
**Tests:** +27 unit/slice (`TokenServiceTest` 6, `CurrentUserTest` 6, `JwtConfigTest` 5,
`AuthControllerTest` 5, `AuthServiceTest` 4) and +8 IT (`AuthApiIT`). `IntegrationTest` now logs
in over HTTP for a real token (`asAdmin()`, `asCustomer()`, `login()`, `withToken()`);
`TestAuthentication` installs a `Jwt` principal. `ProductApiIT` lost its bad-credentials test to
`AuthApiIT`. Test report: `docs/test-reports/phase-09.md`.
**Gotchas:** `@WebMvcTest` slices now need `JwtConfig` imported too — a resource server cannot be
built without a `JwtDecoder` — which is why the `AuthenticationManager` had to move out of
`SecurityConfig` (a slice has no `UserDetailsService`). `Jwt.getIssuer()` insists on a URL, so a
plain-string issuer must be read with `getClaimAsString("iss")`; the decoder's issuer validator
compares strings and is fine with it. The resource server has its own `AuthenticationEntryPoint`
for bad tokens, separate from `exceptionHandling()`'s for missing ones — both must be set or half
the 401s lose the `ApiError` shape. A `uid` claim comes back as `Integer` or `Long` depending on
its size. The decoder allows 60 s of clock skew, so short expiries cannot be tested by waiting.
bash 3.2 mis-splits escaped quotes nested in a command substitution inside a quoted string.
**Follow-ups (not done, out of scope):** a refresh token endpoint (optional in the phase file,
deliberately skipped — it needs storage, rotation and reuse detection). Revocation via a token
deny-list. RS256 and a JWKS endpoint, needed as soon as a second service accepts these tokens —
relevant from Phase 20. TLS termination, which a Bearer token really requires — the deployment
phases.

## Phase 08: Spring Security (tag: phase-08-complete, PR #8)
**What exists now:** The application knows who is calling. `users` (V5) holds BCrypt-hashed
accounts with roles CUSTOMER and ADMIN; the ADMIN (`admin`/`admin123`) is seeded by the
migration because registration always creates a CUSTOMER. HTTP Basic on a stateless chain:
product reads public, product writes ADMIN, cart and orders CUSTOMER, everything else
authenticated. V6 gave every account its own cart and stamped every order with its owner, so the
shared cart is gone and a stranger's order is a 403. 186 tests (163 + 23), smoke test 110 checks.
**Key code:** `security/SecurityConfig` (the filter chain and the ordered rules, `@EnableWebSecurity`
+ `@EnableMethodSecurity`, `BCryptPasswordEncoder` bean, CSRF off, sessions STATELESS);
`security/AppUserDetailsService` + `AppUserDetails` (the adapter that adds the `ROLE_` prefix and
carries the account id); `security/CurrentUser` (a bean over `SecurityContextHolder`, injected by
`CartService`, `OrderService`, `OrderPlacementService`, `CustomerService`);
`security/ApiErrorWriter` + `ApiErrorAuthenticationEntryPoint` + `ApiErrorAccessDeniedHandler`
(401/403 in the `ApiError` shape); `customer/` (User, Role, UserRepository, CustomerService,
CustomerController, dto/). `OrderService.findAll` is `@PreAuthorize("hasRole('CUSTOMER')")` over a
user-scoped query; `findById` adds `@PostAuthorize("returnObject.username() == authentication.name")`.
**Config & infrastructure:** New dependencies `spring-boot-starter-security` and (test)
`spring-boot-starter-security-test`; Spring Security 7.1.1 from the BOM. No new properties.
Migrations V5 (`users` + seeded admin) and V6 (`cart.user_id` UNIQUE NOT NULL, `orders.user_id`
NOT NULL + `idx_orders_user`) applied incrementally to the live Phase 7 database, now at v6.
OpenAPI declares a `basicAuth` scheme, so Swagger UI has an Authorize button.
**Tests:** +43 unit/slice (`CustomerServiceTest` 8, `CustomerControllerTest` 11,
`AppUserDetailsServiceTest` 4, an `Access` nest in each controller slice, V5/V6 assertions in
`FlywayMigrationTest`, security assertions in `OpenApiDocumentationTest`); +8 IT. New test support:
`support/WithSecurityRules` (imports the real `SecurityConfig` into a `@WebMvcTest`) and
`support/TestAuthentication` (signs a `@SpringBootTest` in as a persisted account). Test report:
`docs/test-reports/phase-08.md`.
**Gotchas:** `@WebMvcTest` auto-configures Spring Security but does NOT pick up your own
`SecurityFilterChain` — without an explicit import the slice runs Boot's "authenticate everything"
fallback and a 401 assertion passes while proving nothing. `@WithMockUser` cannot be used in the
`@SpringBootTest` classes: `CurrentUser` needs an `AppUserDetails` with a real database id.
Spring Boot 4 defines no `com.fasterxml.jackson.databind.ObjectMapper` bean (Jackson 3's
`tools.jackson.databind.json.JsonMapper` is the one to inject) even though Jackson 2 is on the
classpath. `SecurityContextHolder` is a ThreadLocal, so `ConcurrentCheckoutTest`'s worker threads
must authenticate themselves. URL rules are ordered and first-match-wins: the public GET rule for
products must precede the ADMIN rule. macOS bash 3.2 makes `"${arr[@]}"` on an empty array an
error under `set -u`, which is why the smoke test's auth code uses no arrays.
**Follow-ups (not done, out of scope):** JWT instead of Basic, so a BCrypt verification is not paid
per request — Phase 9. Password change, account lockout and login rate limiting — not planned.
An admin view over all orders — not planned; the repository deliberately has no "all orders" query.

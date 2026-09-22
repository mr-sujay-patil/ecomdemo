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

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

## Phase 10: Containerization (tag: pending, PR: pending)
**What exists now:** `cp .env.example .env && docker compose up --build` starts the whole system:
`ecomdemo-app` and `ecomdemo-db` on a private network, the app waiting for `pg_isready` before it
connects, the data in a named volume that survives `down`. The image is multi-stage — JDK+Maven
build, JRE runtime — 410 MB, non-root uid 1001, layered jar, heap sized from the container limit.
No application code changed; 190 + 30 tests unchanged, smoke test 125 checks and now runs against
the stack. Schema still V6.
**Key code:** `Dockerfile` (two stages; dependencies resolved before the source is copied;
`jarmode=tools extract --layers --launcher`; `addgroup/adduser` at a pinned uid 1001;
`JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`; a `HEALTHCHECK` on
`/api/products`; `ENTRYPOINT sh -c "exec java ..."` so the JVM is PID 1 and gets SIGTERM).
`compose.yaml` (db + app, `depends_on: condition: service_healthy`, named volume,
`deploy.resources.limits.memory`, every value `${VAR:-default}`). `.dockerignore`, `.env.example`.
**Config & infrastructure:** No new Maven dependencies. New env vars via `.env`: `JWT_SECRET`,
`POSTGRES_*`, `APP_PORT`, `APP_MEMORY_LIMIT`, `JAVA_OPTS`, `SPRING_PROFILES_ACTIVE`. The app
reaches PostgreSQL at `db:5432` over the compose network, never the published host port. The
pre-compose container `ecomdemo-postgres` is stopped (port clash) but not deleted.
**Tests:** No Java tests added or changed. `scripts/smoke-test.sh` now finds `ecomdemo-db` before
`ecomdemo-postgres`, and its persistence probe records PostgreSQL's `system_identifier` so a
different database reads as a first run rather than as lost data; `psql_query` moved up to the
helpers. Test report: `docs/test-reports/phase-10.md`.
**Gotchas:** `postgres:18` changed its data directory — mount `/var/lib/postgresql`, NOT
`/var/lib/postgresql/data`, or the container refuses to start. A bare `depends_on: [db]` waits
only for "started", not "accepting connections". `-XX:MaxRAMPercentage` needs a memory limit on
the service or it is a percentage of the whole host; the JVM's own default is 25%, measured here
as 192 MB of a 768 MB container against 576 MB with the flag. `./mvnw verify` still works while
the stack is up (Testcontainers binds random ports) but the two compete for the daemon, so it
takes ~78 s instead of ~33 s. Buildpacks measured at 766 MB / 53 s rebuild against the
Dockerfile's 410 MB / 5 s.
**Follow-ups (not done, out of scope):** building and publishing the image in CI — Phase 11.
Image vulnerability scanning — Phase 31. A production-shaped deployment (no local database, real
secret management, more than one replica) — Phases 25-26. Pinning base images by digest and
signing them — not planned.

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

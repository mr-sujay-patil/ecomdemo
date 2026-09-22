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

## Phase 07: Integration Testing (tag: phase-07-complete, PR #7)
**What exists now:** `./mvnw verify` starts PostgreSQL 18 in a Testcontainers container, applies
V1-V4 to it and drives the whole application over real HTTP against it. 135 tests: 120 under
Surefire (unchanged, H2, ~9 s) and 15 under Failsafe (`*IT`, PostgreSQL, ~8 s including the
container). The Phase 6 oversell race is now proven against PostgreSQL's own locking in the
build, not just against H2 and the hand-run smoke test.
**Key code:** `support/PostgresContainerConfig` declares `PostgreSQLContainer` as a
`@TestConfiguration` `@Bean` with `@ServiceConnection` (image pinned `postgres:18-alpine`).
`support/IntegrationTest` is the base class every `*IT` extends: `@SpringBootTest(RANDOM_PORT)` +
`@AutoConfigureTestRestTemplate` + `@Import(PostgresContainerConfig.class)` +
`@ActiveProfiles("it")`, and it holds the `protected TestRestTemplate rest`. `ProductApiIT` (5),
`CartApiIT` (6), `OrderApiIT` (4, including the race).
**Config & infrastructure:** New test-scope dependencies: `spring-boot-testcontainers`,
`org.testcontainers:testcontainers-postgresql` (2.0.5 from the BOM), and
`org.springframework.boot:spring-boot-restclient` (TestRestTemplate needs `RestTemplateBuilder`).
`maven-failsafe-plugin` bound to BOTH `integration-test` and `verify`, with the same Mockito
`-javaagent` argLine Surefire uses. `src/test/resources/application-it.properties` sets a small
Hikari pool and deliberately NO datasource url. Docker must be running for `verify`; `./mvnw test`
still needs nothing.
**Tests:** +15, all new files; no existing test was edited, moved or deleted. Test report:
`docs/test-reports/phase-07.md`. The smoke test is unchanged at 78 checks.
**Gotchas:** In Testcontainers 2.x the module is `testcontainers-postgresql` (1.x called it
`postgresql`) and the class is `org.testcontainers.postgresql.PostgreSQLContainer`, non-generic;
the old `org.testcontainers.containers.PostgreSQLContainer` is still on the classpath and is the
wrong one. Spring Boot 4 moved `TestRestTemplate` to `org.springframework.boot.resttestclient`
and registers its auto-configuration ONLY through `@AutoConfigureTestRestTemplate` — without it
the field is simply not injected. Every annotation on `IntegrationTest` is part of the context
cache key: add a `@MockitoBean` to one subclass and that class silently gets its own context AND
its own container. Failsafe bound to `integration-test` alone does NOT fail the build; the
separate `verify` goal is what does. Surefire's `*Test.java` and Failsafe's `*IT.java` patterns
do not overlap, so the file name is the whole mechanism for choosing a suite.
**Follow-ups (not done, out of scope):** giving CI a Docker daemon so `verify` can run there -
Phase 11. Migrating `ConcurrentCheckoutTest`/`FlywayMigrationTest` onto Testcontainers, and
`withReuse(true)` for the inner loop - not planned. Per-user carts would remove the shared-cart
cleanup dance in `CartApiIT` and `OrderApiIT` - Phase 8.

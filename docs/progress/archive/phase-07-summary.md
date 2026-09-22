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

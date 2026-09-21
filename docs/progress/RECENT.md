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

## Phase 07: Integration Testing (tag: pending, PR: pending)
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

## Phase 06: Transactions & Concurrency (tag: phase-06-complete, PR #6)
**What exists now:** Checkout is one database transaction, and the last unit of a product can be
sold exactly once. A failure anywhere in a checkout leaves the catalogue, the cart and the order
history as they were; two simultaneous checkouts for the same stock end as one 201 and one 409,
never two orders. Every attempt, successful or refused, is recorded in `order_audit`.
**Key code:** `OrderPlacementService.placeOnce()` (`@Transactional`, the whole unit of work) and
`OrderService.place()` (no transaction, three attempts, then `ConcurrentUpdateException` -> 409)
are SEPARATE beans, so the retry crosses the proxy. `OrderAuditService.record(...)` is
`Propagation.REQUIRES_NEW`. `Product.version` is `@Version` with no getter. `ProductService` is
class-level `readOnly = true` with four writers overriding it; `CartService` is read-write
throughout. `ConcurrentUpdateException extends ConflictException`; `GlobalExceptionHandler` also
maps a raw `OptimisticLockingFailureException` to 409.
**Config & infrastructure:** No new dependencies and no new properties. Migration
`V4__add_product_version_and_order_audit.sql` adds `product.version BIGINT NOT NULL DEFAULT 0`
and the `order_audit` table (no foreign key, `order_id` nullable, `idx_order_audit_recorded_at`).
It applied incrementally to the live Phase 5 database: "Successfully applied 1 migration ... now
at version v4".
**Tests:** 120 total, was 108. `ConcurrentCheckoutTest` (`@SpringBootTest`, real threads and a
CountDownLatch) adds 4; `OrderPlacementServiceTest` holds the 6 place-order unit tests moved out
of `OrderServiceTest` plus 3 on auditing; `OrderServiceTest` is rewritten around the retry budget
(8, was 10); `FlywayMigrationTest` adds 1. The smoke test grows from 65 checks to 78.
**Gotchas:** `@Transactional` does nothing when a method is called from its own class — that is
why the retry and the unit of work are two beans, and why `CartService.view()` cannot be
`readOnly` (it calls `currentCart()`, which creates the cart on first use, and manual flush mode
would silently drop that insert). A `readOnly` transaction that JOINS a read-write one does not
make it read-only: the outer transaction's settings win, which is what lets
`ProductService.requireProduct` hand back a product the checkout then modifies. Every
`@SpringBootTest` shares one H2 database, so a test that creates rows pollutes the next class —
`FlywayMigrationTest` now counts V2's ten seeded names instead of the whole table, and
`ConcurrentCheckoutTest` deletes its own products in `@AfterEach`. In the smoke test two
backgrounded `curl`s do not reliably race; `curl --parallel --parallel-immediate` does.
**Follow-ups (not done, out of scope):** running the race against real PostgreSQL in the build
rather than by hand — Phase 7 (Testcontainers). An endpoint over `order_audit`, a
`CHECK (stock_quantity >= 0)`, and backoff between retries — not planned. Per-user carts remove
the "two checkouts of one shared cart" oddity — Phase 8.

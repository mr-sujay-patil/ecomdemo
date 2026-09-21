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

## Phase 06: Transactions & Concurrency (tag: pending, PR: pending)
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

## Phase 05: Database Migrations (tag: phase-05-complete, PR #5)
**What exists now:** The schema is version-controlled. Three Flyway migrations build the database
from nothing; Hibernate creates and alters nothing and only validates (`ddl-auto=validate`),
failing startup if the entities and the schema disagree. `Product` gained an optional `category`,
exposed on `ProductRequest`/`ProductResponse`.
**Key code:** `src/main/resources/db/migration/V1__init_schema.sql` (the five tables, readable
constraint names, explicit `ON DELETE`, two FK indexes), `V2__seed_products.sql` (the catalogue,
replacing the deleted `data.sql`), `V3__add_product_category.sql` (nullable column + backfill +
`idx_product_category`). `FlywayMigrationTest` (`@SpringBootTest`) asserts the applied versions,
the checksums, the history table, the seed and V3's effects.
**Config & infrastructure:** `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`
(12.4.0 from the BOM). `application.properties`: `ddl-auto=validate`,
`spring.flyway.locations=classpath:db/migration`, `baseline-on-migrate=false`,
`validate-on-migrate=true`; `spring.sql.init.mode` and `defer-datasource-initialization` are gone.
`application-test.properties` also uses `validate` — the suite runs the same migrations on H2. The
`ecomdemo-postgres` container was recreated from scratch this phase.
**Tests:** 108 total, was 102 — `FlywayMigrationTest` adds 5 and `DatasourceConfigurationTest` 1.
The smoke test grows from 52–54 checks to 62–65 and gained a SKIP state for checks it cannot run.
**Gotchas:** Spring Boot 4 splits auto-configuration per technology, so bare `flyway-core` wires
up NOTHING — the migrations silently never run. Use `spring-boot-starter-flyway`. `@DataJpaTest`
does not include Flyway either: both repository slices keep `ddl-auto=create-drop` on their own
throwaway database (their old `spring.sql.init.mode=never` override became that). Flyway creates
`flyway_schema_history` and its columns in lower case, so SQL against it must quote every
identifier — H2 folds unquoted names to upper case. On H2 (not PostgreSQL) Flyway also writes a
rank-0 row with a null version for creating the history table; filter it out with
`version IS NOT NULL`. V3's column is nullable on purpose (expand/contract) — making it `NOT NULL`
later is a separate migration.
**Follow-ups (not done, out of scope):** running the migrations against real PostgreSQL —
Phase 7. Tightening `category` to `NOT NULL`, and a `category` filter on `GET /api/products` —
not planned. Flyway community has no `undo`; a bad migration is fixed by the next one.

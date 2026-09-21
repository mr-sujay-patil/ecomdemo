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

## Phase 05: Database Migrations (tag: pending, PR: pending)
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

## Phase 04: PostgreSQL (tag: phase-04-complete, PR #4)
**What exists now:** The application stores its data in a real PostgreSQL 18 server; data survives
a restart. Configuration is split into profiles — `dev` (PostgreSQL, the default) and `test`
(in-memory H2), so `./mvnw clean verify` still needs nothing running. No production Java changed
this phase; it is all dependencies, configuration and the seed script.
**Key code:** Nothing in `src/main/java` changed. `DatasourceConfigurationTest` asserts what each
profile resolves to using `ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`,
without connecting to anything.
**Config & infrastructure:** `application.properties` keeps only what every profile shares and
sets `spring.profiles.active=dev`. `application-dev.properties` (main resources) holds the
PostgreSQL URL as `${POSTGRES_HOST:localhost}` / `PORT` / `DB` / `USER` / `PASSWORD` placeholders
plus the Hikari pool (`EcomdemoPool`, max 10). `application-test.properties` lives in **test**
resources and points at `jdbc:h2:mem:ecomdemo;MODE=PostgreSQL`. `ddl-auto` is `update` for the app
and `create-drop` for tests. Dependencies: `org.postgresql:postgresql` (runtime, version from the
Boot BOM) replaces H2 at runtime; H2 drops to `test` scope; `spring-boot-h2console` is gone. Start
the database with `docker run --name ecomdemo-postgres -e POSTGRES_DB=ecomdemo -e
POSTGRES_USER=ecomdemo -e POSTGRES_PASSWORD=ecomdemo -p 5432:5432 -d postgres:18-alpine`, then
`docker start/stop ecomdemo-postgres` after that.
**Tests:** 102 total, was 94 — `DatasourceConfigurationTest` adds 8. The smoke test grows from 48
checks to 52 on a first run and 54 once a probe from a previous run exists.
**Gotchas:** The `test` profile is activated by surefire's `<systemPropertyVariables>` in
`pom.xml`, **not** by a `src/test/resources/application.properties` — a file of that name shadows
the main one instead of merging with it, so every shared setting would be lost. That same system
property also means a test cannot observe the file's default profile; assert the file instead.
`spring.sql.init.mode` must be `always` for PostgreSQL (`embedded` means in-memory only), which is
why `data.sql` is now guarded by `WHERE NOT EXISTS` as one statement — an unguarded INSERT would
re-seed ten products on every restart. `@DataJpaTest` replaces the datasource with an embedded one
regardless of profile, which is why the repository slices never needed changing. Values written to
`.smoke-state` must be quoted: the probe name contains spaces and the file is read back with `.`.
**Follow-ups (not done, out of scope):** Flyway instead of `ddl-auto=update` — Phase 5.
`@Transactional` and the oversell race — Phase 6. Tests against real PostgreSQL with
Testcontainers — Phase 7. Bringing the database up with the app — Phase 10 (Docker Compose).

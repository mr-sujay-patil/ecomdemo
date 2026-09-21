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

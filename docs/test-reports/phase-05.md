# Phase 05 Test Report: Database Migrations (Flyway)

- **Date:** 2026-09-22
- **Branch:** `feature/phase-05-flyway`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  Flyway 12.4.0, PostgreSQL 18.6 (`postgres:18-alpine`, aarch64), Docker 29.7.2
- **Result:** ✅ all checks passed (two springdoc advisory WARNs at startup, carried over from
  Phase 3 — see §6)

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 108, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 8.700 s
```

Nothing is `@Disabled` or skipped. 102 tests carried over from Phases 1–4 and 6 are new.

| Test class | Type | Tests | What it proves |
|---|---|---|---|
| `FlywayMigrationTest` | `@SpringBootTest` | 5 | V1–V3 are applied in order with nothing pending or failed; every applied migration still matches its checksum; `flyway_schema_history` holds one row per migration; V2 seeded exactly ten products, once; V3's column exists, was backfilled, is still nullable, and its index was created |
| `DatasourceConfigurationTest` | `ApplicationContextRunner` | +1 (8 → 9) | `baseline-on-migrate=false`, `validate-on-migrate=true`, and the migration location. Three existing assertions were rewritten for the new configuration: `ddl-auto` is `validate` in both profiles, and no `spring.sql.init.*` property survives |

Test count by layer: **35 unit · 33 web slice · 10 persistence slice · 21 full context ·
9 configuration**.

The strongest evidence is not any single assertion: it is that every `@SpringBootTest` in the
suite loads at all. `ddl-auto=validate` means Hibernate creates nothing and fails startup if one
table, column, type or nullability disagrees with the entities — so if a migration were wrong, or
had not run, those contexts could not be built. The suite therefore runs the same V1–V3 the
application runs, then validates the result.

Two exceptions, both deliberate:

- `CartRepositoryTest` and `OrderRepositoryTest` (`@DataJpaTest`) keep `ddl-auto=create-drop` on
  their own throwaway database. `@DataJpaTest` does not include Flyway's auto-configuration, and
  these tests are written to own the exact rows they assert on — V2 would seed ten products they
  never asked for. Their `spring.sql.init.mode=never` override became the `create-drop` override.
- The suite runs on H2 in PostgreSQL mode, not PostgreSQL. That catches drift between the
  migrations and the entities, not PostgreSQL-specific SQL. Phase 7 closes the gap.

## 2. Phase acceptance — the "Done when"

> A fresh database is built entirely by Flyway, and Hibernate validation passes.

The Phase 4 database was built by `ddl-auto=update` and has no `flyway_schema_history`, so it was
deleted rather than baselined (`docker rm -f ecomdemo-postgres`, then the `docker run` from the
README). Against the empty database:

```
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "1 - init schema"
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "2 - seed products"
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "3 - add product category"
o.f.core.internal.command.DbMigrate : Successfully applied 3 migrations to schema "public",
                                      now at version v3 (execution time 00:00.024s)
com.ecomdemo.EcomdemoApplication    : Started EcomdemoApplication in 2.838 seconds
```

`Started` after `Successfully applied` is the whole claim: Hibernate validated the schema Flyway
had just built and found nothing to complain about. 0 ERROR lines.

```
 installed_rank | version |     description      | type | success
----------------+---------+----------------------+------+---------
              1 | 1       | init schema          | SQL  | t
              2 | 2       | seed products        | SQL  | t
              3 | 3       | add product category | SQL  | t
```

Indexes present in `public`: `idx_cart_item_cart`, `idx_order_item_order`, `idx_product_category`,
plus the five primary keys and Flyway's own two.

## 3. Running the complete application — `./mvnw spring-boot:run`

Started twice.

| Run | Database state | Flyway log | Startup |
|---|---|---|---|
| 1 | empty | `Successfully applied 3 migrations … now at version v3` | `Started EcomdemoApplication in 2.838 seconds`, 0 ERROR |
| 2 | already at v3 | `Successfully validated 3 migrations`, `Current version of schema "public": 3` | `Started EcomdemoApplication in 2.296 seconds`, 0 ERROR |

Run 2 is the "applied exactly once" property: nothing was re-run, the ten seeded products were
not duplicated, and the products created by the smoke test in between were still there.

## 4. End-to-end smoke test — `scripts/smoke-test.sh`

| Run | Checks | Result |
|---|---|---|
| 1 (fresh database, first start) | 62 passed, 0 failed, 0 skipped | exit 0 |
| 2 (after a real restart of the application) | 65 passed, 0 failed, 0 skipped | exit 0 |

The difference is the three persistence checks from Phase 4, which only have something to verify
once a previous run has left a probe behind.

New in this phase (the "Smoke test additions" of the phase file):

```
Flyway migrations
  PASS  flyway_schema_history shows V1-V3, all successful
  PASS  no migration is recorded as failed
  PASS  V3's idx_product_category index exists
  PASS  GET /api/products returns 200
  PASS  every product in the list carries a category field
  PASS  the seeded keyboard was backfilled by V3
  PASS  a product can be created with a category
  PASS  and the category comes back
  PASS  a product can be created without one
  PASS  and comes back with category null
  PASS  the two category probe products are cleaned up
```

The history checks need SQL rather than HTTP, so they use a `psql` on `PATH` if there is one and
otherwise run `psql` inside the container named by `POSTGRES_CONTAINER` (default
`ecomdemo-postgres`). With neither available they print **SKIP** with a reason and are counted
separately in the summary — per the protocol's honesty rule, a check that could not run is never
reported as a pass. Both runs above had Docker available, so nothing was skipped.

## 5. Failure scenario — editing a migration that has already been applied

Not required by the phase, but it is the rule the whole mechanism rests on, so it was verified
rather than asserted. A comment line was appended to `V1__init_schema.sql` and the application
started against the database that had already run the original:

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 1
-> Applied to database : -1645725570
-> Resolved locally    : 1764594157
Either revert the changes to the migration, or run repair to update the schema history.
```

The context failed to initialise and the JVM exited 1 — the application refused to start rather
than run against a schema whose history it could no longer trust. `V1__init_schema.sql` was
restored afterwards (`git diff` clean) and the suite re-run green.

## 6. Startup log

0 ERROR. 2 WARN, both springdoc's, unchanged since Phase 3 and deliberate:

```
SpringDoc /v3/api-docs endpoint is enabled by default. To disable it in production, set the
property 'springdoc.api-docs.enabled=false'
SpringDoc /swagger-ui.html endpoint is enabled by default. To disable it in production, set the
property 'springdoc.swagger-ui.enabled=false'
```

Phase 8 decides access to those paths.

## 7. Cleanup

The application was stopped after testing. The `ecomdemo-postgres` container is left **running**
with the migrated database (schema at v3, ten seeded products plus one smoke-test probe), so the
next session can start the application immediately; `docker start ecomdemo-postgres` if it is
down. No stray processes. `.smoke-state` is untracked, as before.

## 8. Manual verification needed

None. Every "Done when" and every smoke-test addition is covered by an automated check above.

# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 5: Database Migrations (Flyway)
- **Branch:** feature/phase-05-flyway
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #5 — https://github.com/mr-sujay-patil/ecomdemo/pull/5 (open, awaiting review)
- **Waiting for user:** YES — review and merge PR #5, then say `merged, continue`

## Phase 04 merge verification (passed 2026-09-21)
PR #4 MERGED with a merge commit (1e7818d, 2 parents: 3955176 + 683d494); branch is an ancestor
of `main`; no commits or file diffs between branch and `main`; remote and local branches intact;
every "What you'll implement" item present in `main` (`org.postgresql:postgresql` runtime + H2 at
test scope in `pom.xml`, `application-dev.properties`, `src/test/resources/application-test.properties`,
`${POSTGRES_*:default}` placeholders, `ddl-auto=update`, the persistence-across-restarts section in
`scripts/smoke-test.sh`, `docs/test-reports/phase-04.md`);
`./mvnw clean verify` on `main` → BUILD SUCCESS, 102 tests, 0 failures; `scripts/smoke-test.sh`
→ 54 passed, 0 failed, exit 0, 0 ERROR in the app log (only springdoc's 2 advisory WARNs), and the
probe product written before the restart was still present (id=12); tag `phase-04-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] `V1__init_schema.sql` and `V2__seed_products.sql` (replacing `data.sql`)
- [x] `spring.jpa.hibernate.ddl-auto=validate`
- [x] `V3__add_product_category.sql` (a new column plus an index) to practice schema evolution
- [x] Smoke test additions: `flyway_schema_history` shows V1–V3 successful; product responses
      include `category`
- [x] Testing protocol run in full + docs/test-reports/phase-05.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 03 archived), tracker → 🔵
- [x] PR raised (#5)

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, Tests run: 108, Failures: 0, Errors: 0,
  Skipped: 0 (6 new: 5 in `FlywayMigrationTest`, 1 in `DatasourceConfigurationTest`)
- 2026-09-22: `./mvnw spring-boot:run` against a DROPPED AND RECREATED database -> Flyway
  "Successfully applied 3 migrations ... now at version v3", then "Started EcomdemoApplication in
  2.838 seconds", 0 ERROR, 2 WARN (springdoc's). Restart -> "Successfully validated 3 migrations",
  "Current version of schema public: 3", nothing re-applied.
- 2026-09-22: `scripts/smoke-test.sh` -> run 1: 62 passed / 0 failed / 0 skipped; real restart;
  run 2: 65 passed / 0 failed / 0 skipped, exit 0.
- 2026-09-22: failure scenario - appending a line to the applied V1 makes startup fail with
  "Migration checksum mismatch for migration version 1" and exit 1. V1 restored, suite green.

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ✅)
- `spring-boot-starter-flyway`, not bare `flyway-core`: Boot 4 splits auto-configuration into a
  module per technology, so flyway-core alone wires up nothing and the migrations never run.
- V1 reproduces the Phase 4 schema exactly (validate compares them) but with readable constraint
  names and explicit ON DELETE rules - Hibernate's validator ignores both.
- V3's `category` is NULLABLE: expand/contract, so the migration cannot break old instances still
  inserting during a deploy.
- The test suite runs the SAME migrations on H2 (MODE=PostgreSQL) with `ddl-auto=validate`, so
  drift between migrations and entities fails the build. The two `@DataJpaTest` repository slices
  are the exception - they keep `create-drop` on their own throwaway database, because
  `@DataJpaTest` does not run Flyway and those tests want empty tables.
- The database was dropped and recreated rather than baselined (`baseline-on-migrate=false`).

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) was RECREATED this phase and
is left RUNNING, migrated to v3. `docker start ecomdemo-postgres` if it is down. The application
itself is stopped.

## Next action
STOPPED at the mandatory post-PR stop point. Wait for the user.
- If they say `merged, continue` -> run merge verification (execution-protocol §5) on `main`:
  `./mvnw clean verify` and `scripts/smoke-test.sh` (needs the `ecomdemo-postgres` container up
  and the app running), the git-workflow Verification Checklist, then tag and push
  `phase-05-complete`, then start Phase 6 (`docs/phases/phase-06-transactions.md`).
- If they say `changes: <feedback>` -> back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say exactly `approved, merge it`.

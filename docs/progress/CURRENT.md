# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 5: Database Migrations (Flyway)
- **Branch:** feature/phase-05-flyway
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

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
- [ ] `V1__init_schema.sql` and `V2__seed_products.sql` (replacing `data.sql`)
- [ ] `spring.jpa.hibernate.ddl-auto=validate`
- [ ] `V3__add_product_category.sql` (a new column plus an index) to practice schema evolution
- [ ] Smoke test additions: `flyway_schema_history` shows V1–V3 successful; product responses
      include `category`
- [ ] Testing protocol run in full + docs/test-reports/phase-05.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 03 archived), tracker → 🔵
- [ ] PR raised

## Last test run
- none yet this phase

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING and holds the
Phase 4 data, including smoke-test probe products. `docker start ecomdemo-postgres` if it is down.
NOTE: that database was built by Hibernate `ddl-auto=update`, so it has no `flyway_schema_history`.
Flyway will need `baseline-on-migrate` or, cleaner for a learning project, a **fresh** database
(`docker rm -f ecomdemo-postgres` and recreate) so V1 builds the schema from nothing. Decide this
as the first implementation step and record it here.

## Next action
Step 5 (IMPLEMENTING). Write `src/main/resources/db/migration/V1__init_schema.sql` matching the
current entities (`Product`, `Cart`, `CartItem`, `Order`, `OrderItem` — check the real table and
column names by starting the app once with `ddl-auto=update`, or by reading the entity classes in
`src/main/java/com/ecomdemo/**`). Add the `flyway-core` + `flyway-database-postgresql`
dependencies. Then V2 (seed, replacing `data.sql`), then switch `ddl-auto` to `validate`, then V3.

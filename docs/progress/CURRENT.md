# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 4: PostgreSQL
- **Branch:** feature/phase-04-postgresql
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #4 — https://github.com/mr-sujay-patil/ecomdemo/pull/4 (open, awaiting review)
- **Waiting for user:** YES — review and merge PR #4, then say `merged, continue`

## Phase 03 merge verification (passed 2026-09-21)
PR #3 MERGED with a merge commit (3955176, 2 parents: 513dc40 + c5480bb); branch is an ancestor
of `main`; no commits or file diffs between branch and `main`; remote and local branches intact;
every "What you'll implement" item present in `main` (springdoc 3.1.1 in `pom.xml`, `OpenApiConfig`,
the four `springdoc.*` properties, `@Tag`/`@Operation`/`@ApiResponse` on all three controllers,
`@Schema` on the DTOs, `OpenApiDocumentationTest`, `docs/test-reports/phase-03.md`);
`./mvnw clean verify` on `main` → BUILD SUCCESS, 94 tests, 0 failures; `scripts/smoke-test.sh`
→ 48 passed, 0 failed, exit 0, 0 ERROR and only springdoc's 2 advisory WARNs in the app log;
tag `phase-03-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] Replace H2 with the PostgreSQL driver; run PostgreSQL via a single `docker run` documented
      in the README
- [x] Profiles: `application-dev.properties` and `application-test.properties`
- [x] Credentials from environment variables, with local defaults in the dev profile
- [x] Keep `ddl-auto=update` for now
- [x] Smoke test addition: create a product, restart the application, confirm it still exists
- [x] Testing protocol run in full + docs/test-reports/phase-04.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 02 archived), tracker → 🔵
- [x] PR raised (#4)

## Last test run
- 2026-09-21: `./mvnw clean verify` → BUILD SUCCESS, Tests run: 102, Failures: 0, Errors: 0,
  Skipped: 0 (8 new in `DatasourceConfigurationTest`)
- 2026-09-21: `./mvnw spring-boot:run` on a fresh database → "Started EcomdemoApplication in
  2.261 seconds", 0 ERROR, 2 WARN (springdoc's, carried over from Phase 3); 5 tables created,
  10 products seeded
- 2026-09-21: `scripts/smoke-test.sh` → run 1: 52 passed / 0 failed; restart; run 2: 54 passed /
  0 failed, exit 0 — the probe created before the restart was still there

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ✅)
- postgres:18-alpine via one `docker run`; the app on PostgreSQL but the suite still on H2 until
  Phase 7; the `test` profile activated by surefire rather than a test-classpath
  application.properties (which would shadow the main one); `${POSTGRES_*:default}` placeholders
  with throwaway local defaults in Git; `data.sql` guarded by `WHERE NOT EXISTS` as one statement;
  H2 in `MODE=PostgreSQL`; the persistence check spans two smoke runs via `.smoke-state`.

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is left RUNNING with its
data, so the app can be started immediately. `docker start ecomdemo-postgres` if it is down.

## Next action
STOPPED at the mandatory post-PR stop point. Wait for the user.
- If they say `merged, continue` → run merge verification (execution-protocol §5) on `main`:
  `./mvnw clean verify` and `scripts/smoke-test.sh` (needs the `ecomdemo-postgres` container up
  and the app running), the git-workflow Verification Checklist, then tag and push
  `phase-04-complete`, then start Phase 5 (`docs/phases/phase-05-flyway.md`).
- If they say `changes: <feedback>` → back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say exactly `approved, merge it`.

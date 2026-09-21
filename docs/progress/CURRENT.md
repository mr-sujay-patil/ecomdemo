# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 7: Integration Testing (Testcontainers)
- **Branch:** feature/phase-07-testcontainers
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 06 merge verification (passed 2026-09-22)
PR #6 MERGED with a merge commit (cf50c2f, 2 parents: 67d3453 + 344c957); branch is an ancestor of
`main`; no commits and no file diffs between branch and `main`; local and remote branches intact;
every "What you'll implement" item present in `main` (`OrderPlacementService`, `OrderAudit*`,
`@Version` on `Product`, `readOnly` on the read services, `REQUIRES_NEW` audit,
`V4__add_product_version_and_order_audit.sql`, `ConcurrentCheckoutTest`, the smoke test's race
section, `docs/test-reports/phase-06.md`); `./mvnw clean verify` on `main` -> BUILD SUCCESS,
120 tests, 0 failures; the app on `main` started with "Successfully validated 4 migrations" /
"Current version of schema public: 4"; `scripts/smoke-test.sh` -> 78 passed, 0 failed, 0 skipped,
0 ERROR in the app log (only springdoc's 2 advisory WARNs); tag `phase-06-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [ ] The Testcontainers PostgreSQL module with `@ServiceConnection`
- [ ] One HTTP-level integration test per feature
- [ ] A shared, reusable container configuration
- [ ] Surefire for unit tests and Failsafe for `*IT.java` tests
- [ ] Testing protocol run in full + docs/test-reports/phase-07.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 05 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22 (on `main`, merge verification): `./mvnw clean verify` -> BUILD SUCCESS, 120 tests,
  0 failures; `scripts/smoke-test.sh` -> 78 passed, 0 failed, 0 skipped.

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at **v4**.
`docker start ecomdemo-postgres` if it is down. The application is stopped, no stray Java
processes. Docker Desktop must stay running from this phase onwards (Testcontainers needs it).

## Next action
Add the Testcontainers BOM/dependencies (`org.testcontainers:postgresql`,
`org.springframework.boot:spring-boot-testcontainers`, `junit-jupiter`) to `pom.xml`, then write
the shared container configuration under `src/test/java/com/ecomdemo/` with `@ServiceConnection`.

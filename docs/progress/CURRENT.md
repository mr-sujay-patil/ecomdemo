# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-21
- **Phase:** 4: PostgreSQL
- **Branch:** feature/phase-04-postgresql
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

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
- [ ] Replace H2 with the PostgreSQL driver; run PostgreSQL via a single `docker run` documented
      in the README
- [ ] Profiles: `application-dev.properties` and `application-test.properties`
- [ ] Credentials from environment variables, with local defaults in the dev profile
- [ ] Keep `ddl-auto=update` for now
- [ ] Smoke test addition: create a product, restart the application, confirm it still exists
- [ ] Testing protocol run in full + docs/test-reports/phase-04.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 02 archived), tracker → 🔵
- [ ] PR raised

## Last test run
- none yet this phase (baseline on `main`: 94 tests, smoke 48 checks)

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- (none yet)

## Next action
Step 5 (IMPLEMENTING): swap the H2 dependency for the PostgreSQL driver in `pom.xml`, then split
`application.properties` into the shared base plus `application-dev.properties` and
`application-test.properties`. PostgreSQL runs locally via `docker run` (Docker Desktop was
started from this session; confirm the daemon is up before the first test run).

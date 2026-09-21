# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 8: Spring Security
- **Branch:** feature/phase-08-spring-security
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 07 merge verification (passed 2026-09-22)
PR #7 MERGED with a merge commit (fe2e02c, 2 parents: cf50c2f + ae20fd2); branch is an ancestor
of `main`; no commits and no file diffs between branch and `main`; local and remote branches
intact; every "What you'll implement" item present in `main` (`PostgresContainerConfig` with
`@ServiceConnection`, `IntegrationTest` base class, `ProductApiIT`/`CartApiIT`/`OrderApiIT`,
`application-it.properties`, Surefire/Failsafe split in `pom.xml`, `docs/test-reports/phase-07.md`);
`./mvnw clean verify` on `main` -> BUILD SUCCESS, Surefire 120 tests + Failsafe 15 tests,
0 failures; the app on `main` started with "Successfully validated 4 migrations" / "Current
version of schema public: 4"; `scripts/smoke-test.sh` -> 78 passed, 0 failed, 0 skipped, 0 ERROR
in the app log; tag `phase-07-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [ ] `customer` feature: registration (BCrypt), profile, `users` table via Flyway, admin seeded by a migration
- [ ] Roles `CUSTOMER` and `ADMIN` with HTTP Basic authentication
- [ ] Rules: product reads public, product writes ADMIN, cart and orders CUSTOMER
- [ ] Cart per user (replaces the shared cart), orders owned by the user
- [ ] `@PreAuthorize` so users only see their own orders
- [ ] 401 and 403 in the standard error format
- [ ] `@WithMockUser` tests covering allowed and denied access per role
- [ ] Smoke test additions (anon read 200, anon cart 401, customer create product 403, admin 201,
      customer A cannot read customer B's order, full flow as a logged-in customer)
- [ ] Testing protocol run in full + docs/test-reports/phase-08.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 06 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- none yet this phase. Baseline inherited from `main`: 120 Surefire + 15 Failsafe, smoke 78.

## Open issues / blockers
- none.

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet.

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at **v4**.
`docker start ecomdemo-postgres` if it is down. The application is stopped, no stray Java
processes. Docker Desktop must stay running (Testcontainers needs it for `./mvnw verify`).

## Next action
Start step 5 (IMPLEMENTING) of the execution protocol on `feature/phase-08-spring-security`:
read `docs/phases/phase-08-spring-security.md`, then work the checklist above top-down with small
Conventional Commits, beginning with the `users` table migration (V5) and the `customer` feature.

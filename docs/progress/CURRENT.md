# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 10: Containerization
- **Branch:** feature/phase-10-docker
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 09 merge verification (passed 2026-09-22)
PR #9 MERGED with a merge commit (d2ab0a2, 2 parents: 0af4342 + 7678106); branch is an ancestor
of `main`; no commits and no file diffs between branch and `main`; local and remote branches
intact; every "What you'll implement" item present in `main` (`auth/` with AuthController,
AuthService, TokenService, AuthenticationManagerConfig and dto; `security/JwtConfig` +
`JwtProperties`; the three `ecomdemo.jwt.*` properties; `AuthApiIT`, `JwtConfigTest`,
`CurrentUserTest`; `docs/test-reports/phase-09.md`); `./mvnw clean verify` on `main` -> BUILD
SUCCESS, Surefire 190 + Failsafe 30, 0 failures, 0 skipped; the app on `main` started with
"Successfully validated 6 migrations" / "Current version of schema public: 6";
`scripts/smoke-test.sh` -> 125 passed, 0 failed, 0 skipped, 0 ERROR in the app log;
tag `phase-09-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [ ] A multi-stage `Dockerfile` (JRE 21 runtime, non-root user, layered JAR)
- [ ] `compose.yaml` with the app and PostgreSQL: health checks, volumes, environment variables
- [ ] `.env.example`
- [ ] JVM container settings (`-XX:MaxRAMPercentage`)
- [ ] A comparison with Buildpacks (`spring-boot:build-image`) in `docs/decisions.md`
- [ ] `docker compose up` runs the whole system and the curl flow works (the "Done when")
- [ ] Smoke test runs against the compose stack instead of `spring-boot:run`
- [ ] Testing protocol run in full + docs/test-reports/phase-10.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 08 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- none yet this phase. Baseline inherited from `main`: 190 Surefire + 30 Failsafe, smoke 125.

## Open issues / blockers
- **JWT_SECRET in compose.** Phase 9 deliberately left no default signing key in Git. compose
  must not reintroduce one: plan is `.env.example` documenting it, `.env` gitignored, and the
  service reading `${JWT_SECRET:-}` so an unset value still starts (random key + WARN).
- **The smoke test's psql checks** currently reach the container named `ecomdemo-postgres`.
  Compose will name it differently, so the script needs to find it either way or the Flyway
  section starts SKIPping.

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet.

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at **v6**
— this is the pre-compose dev database and port 5432 will clash with the compose stack unless it
is stopped first. The application is stopped, no stray Java processes. Docker Desktop must stay
running. The database holds `admin`, `smoke-customer`, `smoke-customer-b`, all BCrypt-hashed.

## Next action
Start step 5 (IMPLEMENTING) of the execution protocol on `feature/phase-10-docker`: read
`docs/phases/phase-10-docker.md`, then work the checklist above top-down with small Conventional
Commits, beginning with the multi-stage `Dockerfile` and a layered-JAR extraction.

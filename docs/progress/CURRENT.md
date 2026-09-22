# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 10: Containerization
- **Branch:** feature/phase-10-docker
- **Step:** TESTING
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
- [x] A multi-stage `Dockerfile` (JRE 21 runtime, non-root user, layered JAR)
- [x] `compose.yaml` with the app and PostgreSQL: health checks, volumes, environment variables
- [x] `.env.example`
- [x] JVM container settings (`-XX:MaxRAMPercentage`)
- [x] A comparison with Buildpacks (`spring-boot:build-image`) in `docs/decisions.md`
- [x] `docker compose up` runs the whole system and the curl flow works (the "Done when")
- [x] Smoke test runs against the compose stack instead of `spring-boot:run`
- [x] Testing protocol run in full + docs/test-reports/phase-10.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 08 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, Surefire 190 + Failsafe 30, 0 failures,
  0 skipped. No Java test changed this phase. Runs in ~78 s while the compose stack is up (the
  two compete for the Docker daemon) and ~33 s otherwise.
- 2026-09-22: from a clean slate (`down -v`, image deleted, .smoke-state removed)
  `docker compose up -d --build` -> 34 s, app healthy after 26 s, "Successfully applied 6
  migrations ... now at version v6", 0 ERROR in the container log.
- 2026-09-22: `scripts/smoke-test.sh` -> 123 passed on the fresh volume (the two persistence
  sub-checks do not apply on a first run), then 125 passed after `compose restart app`.
  0 failed, 0 skipped both times.
- 2026-09-22: `docker compose down` then `up` -> "Successfully validated 6 migrations" and the
  persistence probe was still there: the named volume outlives the containers.
- 2026-09-22: image checks - runs as uid 1001, no javac, no mvn, no .java in the runtime image;
  PID 1 is `java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError ... JarLauncher`.
  Heap inside the container: 192 MB at the JVM default vs 576 MB with the flag, limit 768 MB.
- 2026-09-22: Buildpacks comparison actually built - 766 MB / 160 s cold / 53 s rebuild, against
  the Dockerfile's 410 MB / 34 s / 5 s.

## Open issues / blockers
- none. Both opening concerns are settled: `JWT_SECRET` has no default in compose.yaml (an unset
  value reaches the app empty, which generates a random key and warns), and the smoke test now
  finds `ecomdemo-db` before `ecomdemo-postgres` so nothing SKIPs.

## Decisions this phase (copied to docs/decisions.md ✅ — 13 entries)
- A hand-written multi-stage Dockerfile over Buildpacks, with the comparison measured, not guessed.
- Dependencies resolved in their own layer before the source is copied (5 s rebuilds).
- Layered jar via `jarmode=tools`, four ordered COPYs.
- Non-root at a PINNED uid 1001 (bind-mounted files are owned by a number).
- `-XX:MaxRAMPercentage=75` plus a memory limit on the service, not a hard-coded -Xmx.
- `depends_on: condition: service_healthy` with pg_isready; the bare form is not enough.
- The app reaches PostgreSQL at `db:5432` over the compose network, never the published port.
- The volume mounts `/var/lib/postgresql` — postgres:18 changed this and the old path refuses
  to start.
- `.env` gitignored, `.env.example` committed, no JWT_SECRET default in compose.yaml.
- The HEALTHCHECK uses `/api/products` because Actuator is Phase 15.
- The smoke test's probe records PostgreSQL's system_identifier, so a new database reads as a
  first run rather than as lost data.

## Environment left behind
The **compose stack is RUNNING and healthy**: `ecomdemo-app` and `ecomdemo-db`, schema v6, data in
the named volume `ecomdemo_postgres-data`. `docker compose up -d` / `docker compose down` to
control it; `down -v` deletes the database.
The pre-compose container `ecomdemo-postgres` is **stopped, not deleted** — it held port 5432.
`docker start ecomdemo-postgres` brings the Phases 4-9 database back, but stop the compose stack
first or they clash.
`.env` exists locally with a real JWT_SECRET and is gitignored. `ecomdemo:latest` is built;
`ecomdemo:buildpack` was deleted after the comparison. No stray Java processes on the host.

## Next action
Push the branch and raise the PR (`gh pr create --base main`), then STOP for the user's review.

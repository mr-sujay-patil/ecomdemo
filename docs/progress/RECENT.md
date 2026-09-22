# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 11: Continuous Integration (tag: phase-11-complete, PRs #11 and #13)
**What exists now:** Every PR is built and tested by GitHub Actions before it can be merged, and
every push to `main` publishes an image to GHCR tagged `sha-<short>` and `latest`. Proven, not
assumed: a deliberately failing test was committed, the run went red with the publish job skipped
and the reports still uploaded, and the commit was reverted. Build ~73 s cold, ~57 s with the
Maven cache. No application code changed; 190 + 30 tests and the 125-check smoke test unchanged.
**Key code:** `.github/workflows/ci.yml` — one workflow, two jobs. `build` (checkout@v7,
setup-java@v6 with `cache: maven`, `./mvnw -B clean verify`, upload-artifact@v7 with
`if: always()`, a `$GITHUB_STEP_SUMMARY` table built from the Surefire/Failsafe XML).
`publish` (`needs: build`, `if: push && ref == refs/heads/main`, `packages: write` on that job
alone, login-action@v4 with the built-in `GITHUB_TOKEN`, metadata-action@v6 for the two tags,
build-push-action@v7 with `cache-from/to: type=gha,mode=max`). `.github/dependabot.yml` — weekly
Maven and github-actions, Spring modules grouped.
**Config & infrastructure:** No Maven dependencies, no application changes, schema still V6. The
only new infrastructure is the GHCR package, created by the first publish. `permissions:
contents: read` workflow-wide. `concurrency` cancels superseded PR runs but never `main` runs.
**Tests:** None added or changed — CI runs the existing suite. `scripts/smoke-test.sh` is
untouched this phase. Test report: `docs/test-reports/phase-11.md`.
**Gotchas:** GitHub's ubuntu runners have a Docker daemon, so Testcontainers works with no
`service:` container and no CI-only datasource — the run log shows `postgres:18-alpine` starting
in 1.4 s. `if: always()` on the upload step is what makes reports available from a RED build
(126 KB artifact captured from the failing run). A red check shows the PR as `UNSTABLE`, NOT
`BLOCKED`, until `required_status_checks` is added to the branch protection — it was `null` at the
time of writing, which is the user's manual step. `needs: build` is the only thing stopping a red
commit from publishing an image.
**Follow-ups (not done, out of scope):** running the smoke test against the compose stack in CI —
optional in the phase file, deliberately skipped, and the most obviously worthwhile next addition.
Image vulnerability scanning — Phase 31. Actual deployment — Phases 25-26. Pinning actions by
commit SHA rather than major version — not planned.

## Phase 10: Containerization (tag: phase-10-complete, PR #10)
**What exists now:** `cp .env.example .env && docker compose up --build` starts the whole system:
`ecomdemo-app` and `ecomdemo-db` on a private network, the app waiting for `pg_isready` before it
connects, the data in a named volume that survives `down`. The image is multi-stage — JDK+Maven
build, JRE runtime — 410 MB, non-root uid 1001, layered jar, heap sized from the container limit.
No application code changed; 190 + 30 tests unchanged, smoke test 125 checks and now runs against
the stack. Schema still V6.
**Key code:** `Dockerfile` (two stages; dependencies resolved before the source is copied;
`jarmode=tools extract --layers --launcher`; `addgroup/adduser` at a pinned uid 1001;
`JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`; a `HEALTHCHECK` on
`/api/products`; `ENTRYPOINT sh -c "exec java ..."` so the JVM is PID 1 and gets SIGTERM).
`compose.yaml` (db + app, `depends_on: condition: service_healthy`, named volume,
`deploy.resources.limits.memory`, every value `${VAR:-default}`). `.dockerignore`, `.env.example`.
**Config & infrastructure:** No new Maven dependencies. New env vars via `.env`: `JWT_SECRET`,
`POSTGRES_*`, `APP_PORT`, `APP_MEMORY_LIMIT`, `JAVA_OPTS`, `SPRING_PROFILES_ACTIVE`. The app
reaches PostgreSQL at `db:5432` over the compose network, never the published host port. The
pre-compose container `ecomdemo-postgres` is stopped (port clash) but not deleted.
**Tests:** No Java tests added or changed. `scripts/smoke-test.sh` now finds `ecomdemo-db` before
`ecomdemo-postgres`, and its persistence probe records PostgreSQL's `system_identifier` so a
different database reads as a first run rather than as lost data; `psql_query` moved up to the
helpers. Test report: `docs/test-reports/phase-10.md`.
**Gotchas:** `postgres:18` changed its data directory — mount `/var/lib/postgresql`, NOT
`/var/lib/postgresql/data`, or the container refuses to start. A bare `depends_on: [db]` waits
only for "started", not "accepting connections". `-XX:MaxRAMPercentage` needs a memory limit on
the service or it is a percentage of the whole host; the JVM's own default is 25%, measured here
as 192 MB of a 768 MB container against 576 MB with the flag. `./mvnw verify` still works while
the stack is up (Testcontainers binds random ports) but the two compete for the daemon, so it
takes ~78 s instead of ~33 s. Buildpacks measured at 766 MB / 53 s rebuild against the
Dockerfile's 410 MB / 5 s.
**Follow-ups (not done, out of scope):** building and publishing the image in CI — Phase 11.
Image vulnerability scanning — Phase 31. A production-shaped deployment (no local database, real
secret management, more than one replica) — Phases 25-26. Pinning base images by digest and
signing them — not planned.

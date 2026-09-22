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

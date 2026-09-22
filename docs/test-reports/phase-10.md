# Phase 10 Test Report: Containerization

- **Date:** 2026-09-22
- **Branch:** `feature/phase-10-docker`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  Docker 29.7.2, Docker Compose v5.4.0, `eclipse-temurin:21-jdk-alpine` (build) and
  `21-jre-alpine` (runtime), `postgres:18-alpine`, curl 8.7.1, bash 3.2.57
- **Result:** ✅ every automated check passed. Nothing is deferred to manual verification.

## 1. Full regression — `./mvnw clean verify`

```
[INFO] --- surefire:3.5.6:test ---
Tests run: 190, Failures: 0, Errors: 0, Skipped: 0

[INFO] --- failsafe:3.5.6:integration-test ---
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
Total time: 01:18 min
```

No test was added or changed this phase — the work is packaging, and the Java behaviour it packages
is already covered. Nothing is `@Disabled`, and Skipped is 0 in both suites.

Worth noting: this ran **with the compose stack up**. Testcontainers binds a random host port, so
the integration tests and the running stack do not collide; the longer wall time is the two
competing for the Docker daemon, not a change in the suite.

## 2. "Done when": `docker compose up` runs the whole system, and the curl flow works

✅ Verified from a completely clean slate — `docker compose down -v`, the image deleted, and
`.smoke-state` removed:

```
docker compose up -d --build        ->  34 s, exit 0
app healthy after 26 s

SERVICE   STATUS
app       Up 34 seconds (healthy)
db        Up 46 seconds (healthy)
```

The application log shows it built the schema for itself on the empty volume:

```
Current version of schema "public": << Empty Schema >>
Successfully applied 6 migrations to schema "public", now at version v6 (execution time 00:00.052s)
Started EcomdemoApplication in 6.106 seconds
```

**0 ERROR lines** in the container log across every run in this report.

The curl flow, against the stack:

```bash
curl -s localhost:8080/api/products
# [{"id":1,"name":"Mechanical Keyboard", ... }]

curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
# a 188-character token

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/customers/me
# {"id":1,"username":"admin","fullName":"Store Administrator","role":"ADMIN", ...}
```

## 3. The image is what it claims to be

| Check | Command | Result |
|---|---|---|
| Runs as a non-root user | `docker compose exec app id` | ✅ `uid=1001(ecomdemo) gid=1001(ecomdemo)` |
| No compiler in the runtime image | `command -v javac` | ✅ absent |
| No Maven in the runtime image | `command -v mvn` | ✅ absent |
| No source code shipped | `find / -name '*.java'` | ✅ nothing |
| The JVM is PID 1, with the flags | `tr '\0' ' ' < /proc/1/cmdline` | ✅ `java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError org.springframework.boot.loader.launch.JarLauncher` |

**The JVM flag, measured inside the container** — the same JVM, asked both ways:

```
default (25%):    192 MB
with JAVA_OPTS:   576 MB
container limit:  768 MB
```

That is the whole argument for `MaxRAMPercentage` in three lines. A modern JVM does read the
container's limit rather than the host's — but it defaults to a quarter of it, so without the flag
this container would run a 192 MB heap and leave 576 MB unused. Setting `-Xmx576m` instead would
be a number that is wrong the moment `APP_MEMORY_LIMIT` changes.

## 4. Layers and caching

| Build | Time |
|---|---|
| Cold, no cache | 34 s (as part of `compose up --build` above) |
| After touching one source file | **5 s** |

Five seconds is the dependency layer being reused. The `pom.xml` is copied and resolved before the
source is, so an ordinary code edit invalidates only the layers after it; the ~64 MB dependency
layer and the ~200 MB Maven download are untouched. Copying the source first would re-resolve
every dependency on every build.

The jar is split with `jarmode=tools extract --layers` into `dependencies`, `spring-boot-loader`,
`snapshot-dependencies` and `application`, and copied in that order — so the layer a code change
rewrites is the ~200 KB one, not the 64 MB fat jar.

## 5. Volumes: data outlives the container

The point of a named volume is that `down` is not destructive. Tested explicitly:

```
docker compose down          # containers removed, volume kept
docker compose up -d
# -> "Successfully validated 6 migrations" / "Current version of schema public: 6"
#    (validated, not re-applied: the schema was already there)
scripts/smoke-test.sh
# -> PASS  the probe product from the previous run is still there (id=18)
```

A container's writable layer is destroyed with it; the volume is not. `docker compose down -v` is
the deliberate way to throw the database away, and that is what produced the empty-schema run in §2.

**A real failure found and fixed here.** The first `compose up` would not start:

```
Counter to that, there appears to be PostgreSQL data in:
  /var/lib/postgresql/data (unused mount/volume)
```

`postgres:18` changed its convention — data now lives in a major-version subdirectory so that
`pg_upgrade --link` can work inside a single mount. Mounting `/var/lib/postgresql/data`, which
every older tutorial shows, makes the container refuse to start. The volume mounts
`/var/lib/postgresql` instead.

## 6. Service ordering

`depends_on: db: condition: service_healthy` with `pg_isready` as the check. The distinction
matters: the bare `depends_on: [db]` waits only for the container to be *started*, which for
PostgreSQL means the process exists — several seconds before it accepts connections. The
application would then die at startup with a Flyway connection error, intermittently.

Observed in §2: the database reached healthy at 46 s of uptime while the application was still
starting, and the application connected on its first attempt with no retry and no error.

## 7. Smoke test — `scripts/smoke-test.sh`

Against the compose stack, not `spring-boot:run`:

```
first run on a fresh volume : 123 passed, 0 failed, 0 skipped
after `compose restart app` : 125 passed, 0 failed, 0 skipped
```

The two-check difference is the persistence probe: on a brand-new database there is no previous
probe to look for, and the script says so rather than inventing a pass —

```
PASS  first run against this database: no previous probe to check (run again after a restart)
```

Zero skips in both runs, so the Flyway section's SQL checks really ran: the script now finds the
compose stack's `ecomdemo-db` container as well as the hand-started `ecomdemo-postgres` of Phases
4–9, without being told which is present.

**A change the phase forced, worth recording.** Moving to the compose stack's fresh volume made
the persistence check fail — correctly, since the probe really was gone, but for a reason that has
nothing to do with whether data survives a restart. The state file now records PostgreSQL's
`system_identifier` alongside the probe id, so a different database reads as a first run rather
than as lost data. Without that, every developer switching to compose would meet a failing test
that is not a bug.

## 8. Buildpacks, measured

The phase asks for a comparison, so both were actually built on this machine rather than compared
from memory:

| | Dockerfile | Buildpacks (`spring-boot:build-image`) |
|---|---:|---:|
| Image size | **410 MB** | 766 MB |
| Cold build | 34 s | 160 s |
| Rebuild after a one-line change | **5 s** | 53 s |
| Runs as non-root | yes (uid 1001, written by us) | yes (uid 1002, automatic) |
| Entrypoint | `java $JAVA_OPTS … JarLauncher` | `/cnb/process/web` |

Buildpacks get the hard parts right with no Dockerfile at all — layering, a non-root user, JVM
container flags, and CVE rebases without a rebuild — which makes them the better default for a
team that wants to stop thinking about images. This project exists to think about them, and the
Dockerfile is what makes the layering, the user and the JVM flags visible and reviewable. It is
also 356 MB smaller and ten times faster to rebuild. The full reasoning is in `docs/decisions.md`.

## 9. Clean-up

- The compose stack is left **running** (`ecomdemo-app`, `ecomdemo-db`), healthy, on schema v6 —
  it is now the way this project runs.
- The pre-compose container `ecomdemo-postgres` was **stopped**, because it held port 5432. It is
  not deleted; `docker start ecomdemo-postgres` brings the Phases 4–9 database back, though the
  compose stack must be down first.
- `ecomdemo:buildpack` was deleted after the comparison. `ecomdemo:latest` remains.
- No stray `java` processes on the host.
- `.env` exists locally with a real `JWT_SECRET` and is gitignored; `.env.example` is committed.

## 10. Nothing deferred

Every "Done when" item and the smoke test addition have an automated check that was actually run.
There is no ⚠️ item in this phase.

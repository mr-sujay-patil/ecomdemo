# Phase 04 Test Report: PostgreSQL

- **Date:** 2026-09-21
- **Branch:** `feature/phase-04-postgresql`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  PostgreSQL 18.6 (`postgres:18-alpine`, aarch64), Docker 29.7.2
- **Result:** ✅ all checks passed (two springdoc advisory WARNs at startup, carried over from
  Phase 3 — see §6)

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 11.281 s
```

Nothing is `@Disabled` or skipped. All 94 tests from Phases 1–3 pass **untouched** — no production
Java changed this phase — and the 8 new ones guard the profile configuration.

| Test class | Type | Tests | What it proves |
|---|---|---|---|
| `DatasourceConfigurationTest` | `ApplicationContextRunner` + `ConfigDataApplicationContextInitializer` | 8 | The dev profile resolves to `jdbc:postgresql://localhost:5432/ecomdemo` with no environment set; all five `POSTGRES_*` variables override host, port, database, user and password; `ddl-auto=update`, so the schema outlives the process; the Hikari pool is named `EcomdemoPool` with a positive maximum; `spring.sql.init.mode=always`; the test profile is in-memory H2 with `create-drop`; and `application.properties` declares `dev` as the default profile |

Test count by layer: **35 unit · 33 web slice · 10 persistence slice · 16 full context ·
8 configuration**.

The configuration tests load the real properties files and resolve the same placeholders the
application resolves at startup, but build no datasource and connect to nothing — so they run on a
machine with no PostgreSQL. Environment variables are simulated with system properties, which sit
in the same Spring `Environment` and go through the identical `${NAME:default}` mechanism.

The suite as a whole still runs on in-memory H2 (`test` profile, now with `MODE=PostgreSQL`). A
green build is therefore **not** proof that the queries work on PostgreSQL; Phase 7 closes that gap
with Testcontainers. What proves PostgreSQL works today is §3–§5 below, run against the real
server.

## 2. Profile activation

Verified that the `test` profile really is the one in force during the build, rather than the
tests passing by accident on the dev settings:

```
$ ./mvnw test -Dtest=PlaceOrderFlowTest
... PlaceOrderFlowTest : The following 1 profile is active: "test"
... HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:ecomdemo user=SA
    Database driver: H2 JDBC Driver
    Database dialect: H2Dialect
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

And that the application uses `dev` and PostgreSQL:

```
$ ./mvnw spring-boot:run
... EcomdemoApplication : The following 1 profile is active: "dev"
... EcomdemoPool - Added connection org.postgresql.jdbc.PgConnection@56299b0e
... Started EcomdemoApplication in 2.261 seconds
```

The pool name `EcomdemoPool` (dev-only) versus `HikariPool-1` (default) is itself the evidence
that the dev profile's Hikari block was applied.

## 3. Running the application — `./mvnw spring-boot:run`

Against a **freshly created, empty** database:

```
Started EcomdemoApplication in 2.261 seconds
ERROR lines: 0
WARN  lines: 2   (both springdoc's, see §6)
```

Schema and seed created on first start:

```
$ docker exec ecomdemo-postgres psql -U ecomdemo -d ecomdemo -c "\dt"
 public | cart       | table | ecomdemo
 public | cart_item  | table | ecomdemo
 public | order_item | table | ecomdemo
 public | orders     | table | ecomdemo
 public | product    | table | ecomdemo
(5 rows)

$ ... -c "SELECT count(*) FROM product;"
 10
```

## 4. End-to-end smoke test — `scripts/smoke-test.sh`

Run once, restart the application, run again:

| Run | Checks | Result |
|---|---|---|
| 1 (fresh database, no previous probe) | 52 passed, 0 failed | exit 0 |
| 2 (after a restart) | 54 passed, 0 failed | exit 0 |

All 48 checks from Phases 1–3 still pass. The new "Persistence across restarts" section, on run 2:

```
Persistence across restarts
  PASS  the probe product from the previous run is still there (id=11)
  PASS  it kept its name
  PASS  it kept its price
  PASS  a probe product is created for the next run
  PASS  the probe reads back from the database
  PASS  probe id 12 recorded in .smoke-state for the next run

Summary: 54 passed, 0 failed
SMOKE TEST PASSED
```

Run 1 has two checks fewer because there is no previous probe to verify yet — it reports
"first run: no previous probe to check" and leaves one behind. That two-run shape is deliberate: a
script cannot restart the application it is talking to, so the restart has to happen between runs.

## 5. Phase acceptance — "Done when: data survives restarts, and all tests pass"

| Claim | How it was verified | Result |
|---|---|---|
| Data survives an **application** restart | Created `Restart Survivor` (id 11) over the API, killed the JVM, started it again, `GET /api/products/11` | ✅ 200 with the same name, description, price and stock |
| Data survives a **database container** restart | `docker stop ecomdemo-postgres` → `docker start` → row count | ✅ 11 rows still there; the data is in a Docker volume, not the container's writable layer |
| The restart check is automated | `scripts/smoke-test.sh`, §4 | ✅ 6 checks |
| Restarting does not re-seed the catalogue | Four application restarts against the same database, then `SELECT count(*) FROM product WHERE name NOT LIKE 'Persistence Probe%'` | ✅ still exactly 10, not 40 |
| Credentials come from the environment, with local defaults | `DatasourceConfigurationTest`, 2 tests | ✅ |
| All tests pass | §1 | ✅ 102/102 |

## 6. Failure scenario — PostgreSQL unreachable

Not required by this phase (the protocol asks for failure checks on resilience phases), but worth
recording because the behaviour changed: with H2 the application could never fail to start for
database reasons, and now it can.

```
$ docker stop ecomdemo-postgres && ./mvnw spring-boot:run
WARN  org.hibernate.orm.jdbc.error : Connection to localhost:5432 refused. Check that the
      hostname and port are correct and that the postmaster is accepting TCP/IP connections.
org.hibernate.exception.JDBCConnectionException: Unable to obtain isolated JDBC connection
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused ...
```

The application **does not start** — it fails fast at schema generation rather than booting and
serving 500s. That is the behaviour you want, and "did you start the container?" is now the first
question when `./mvnw spring-boot:run` fails. Retrying and degrading gracefully is Phase 22's
subject.

The two WARNs in a healthy startup are unchanged from Phase 3 — springdoc noting that
`/v3/api-docs` and `/swagger-ui.html` are enabled. Deliberate; Phase 8 decides access.

## 7. Clean-up

Application stopped, no stray Java processes. The `ecomdemo-postgres` container is left **running**
with its data, so the reviewer can start the app and try it immediately; `docker rm -f
ecomdemo-postgres` removes it and its data. `.smoke-state` is local and Git-ignored.

## 8. Manual verification for the reviewer

Nothing in this phase is unverifiable, so there are no ⚠️ items. To see it yourself:

```bash
docker start ecomdemo-postgres        # or the docker run in the README the first time
./mvnw spring-boot:run

# in a second terminal
curl -X POST http://localhost:8080/api/products -H 'Content-Type: application/json' \
  -d '{"name":"Survivor","description":"still here after a restart","price":10.00,"stockQuantity":1}'

# Ctrl-C the application, start it again, then read the product back by the id you got.
# Then look at the rows directly:
docker exec -it ecomdemo-postgres psql -U ecomdemo -d ecomdemo -c 'SELECT id, name FROM product;'
```

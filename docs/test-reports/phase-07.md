# Phase 07 Test Report: Integration Testing

- **Date:** 2026-09-22
- **Branch:** `feature/phase-07-testcontainers`
- **Toolchain:** JDK 21 (Temurin 21.0.12.1), Maven Wrapper 3.9.16, Spring Boot 4.1.1,
  Testcontainers 2.0.5, Surefire 3.5.6, Failsafe 3.5.6, Flyway 12.4.0,
  PostgreSQL 18.6 (`postgres:18-alpine`, aarch64), Docker 29.7.2, curl 8.7.1
- **Result:** ✅ all automated checks passed. One check is ⚠️ manual — see §9.

## 1. Full regression — `./mvnw clean verify`

```
[INFO] --- surefire:3.5.6:test ---
Tests run: 120, Failures: 0, Errors: 0, Skipped: 0

[INFO] --- failsafe:3.5.6:integration-test ---
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
Total time: 16.928 s
```

**135 tests in total: 120 under Surefire, 15 under Failsafe.** Nothing is `@Disabled` or skipped.
Run three times end to end, green each time (16.9 s, 17.0 s, 16.0 s).

The 120 Surefire tests are exactly the Phase 6 suite, unchanged — no existing test was edited,
moved or deleted this phase. The 15 new ones are integration tests:

| Test class | Tests | What it proves against real PostgreSQL |
|---|---|---|
| `ProductApiIT` | 5 | The seeded catalogue is served after V1–V4 run on an empty PostgreSQL; a created product survives the round trip with `NUMERIC(10,2)` scale intact; update and delete really change the rows; an unknown id is a 404 with the shared `ApiError` body; validation rejects a bad product before the database sees it |
| `CartApiIT` | 6 | An added line is still there on a *second* request, so it was committed; adding the same product twice merges the line instead of duplicating it; totals add up across lines; a quantity change and a removal both take; an unknown product is a 404 and leaves the cart untouched; quantity 0 is a 400 |
| `OrderApiIT` | 4 | A checkout creates the order, empties the cart and reduces stock, and the order reads back through its own endpoint; a checkout that exceeds stock is a 409 that rolls back the line that *would* have succeeded; an empty cart is a 409; **two simultaneous checkouts for the last unit end as one 201 and one 409, with stock 0 and exactly one order** |

Test count by layer: **42 unit · 33 web slice · 10 persistence slice · 26 full context (H2) ·
9 configuration · 15 integration (PostgreSQL)**.

## 2. Phase acceptance — the "Done when"

> **`./mvnw verify` runs both unit and integration tests.** ✅

Proven by the two summary blocks above coming from two different plugins in one command. The
split is by file name and nothing else: Surefire's default patterns take `*Test.java`, Failsafe's
take `*IT.java`, and the sets do not overlap.

Three further properties of the split were verified rather than assumed:

**(a) `./mvnw test` runs the fast suite only, and needs no Docker.**

```
Tests run: 120, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 8.696 s
```

Grepping that build's output: `Creating container for image` appears **0** times and no `*ApiIT`
class is mentioned. The inner development loop is unchanged — 8.7 s, no container, no Docker.

**(b) A failing integration test fails the build — at `verify`, not at `integration-test`.**

A throwaway `TemporarilyFailingIT` was added, `./mvnw clean verify` run, and the file deleted
again (it is not in any commit):

```
[INFO] --- failsafe:3.5.6:integration-test (default) @ ecomdemo ---
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in TemporarilyFailingIT
[ERROR] Tests run: 16, Failures: 1, Errors: 0, Skipped: 0
[INFO] --- failsafe:3.5.6:verify (default) @ ecomdemo ---
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal ...maven-failsafe-plugin:3.5.6:verify (default) on project
        ecomdemo: There are test failures.
```

That is the behaviour the two goals exist for: `integration-test` recorded the failure and let
the build carry on so the container could be torn down, and `verify` then failed the build. With
only the `integration-test` goal bound, this build would have been green.

**(c) One container is started for the whole integration-test run.**

```
tc.testcontainers/ryuk:0.14.0 : Creating container for image: testcontainers/ryuk:0.14.0
tc.postgres:18-alpine        : Creating container for image: postgres:18-alpine
tc.postgres:18-alpine        : Container postgres:18-alpine started in PT1.380188S
```

`Creating container for image` appears twice in a full `verify`: once for PostgreSQL and once for
Ryuk, Testcontainers' own reaper. The timings say the same thing — the first IT class to run pays
about 12 s for the context and the container, and the other two classes then take 0.1–0.3 s each
because they share both.

## 3. Running the complete application — `java -jar target/ecomdemo-0.0.1-SNAPSHOT.jar`

Nothing about the application changed this phase, and the run confirms it:

```
o.f.core.internal.command.DbValidate : Successfully validated 4 migrations (execution time 00:00.017s)
o.f.core.internal.command.DbMigrate  : Current version of schema "public": 4
com.ecomdemo.EcomdemoApplication     : Started EcomdemoApplication in 4.206 seconds
```

`ERROR` count in the application log: **0**. The only WARNs are springdoc's two advisories about
`/v3/api-docs` and `/swagger-ui.html` being enabled by default, carried over from Phase 3.

## 4. End-to-end smoke test — `scripts/smoke-test.sh`

The phase file says "No new checks. The existing script must still pass."

```
Summary: 78 passed, 0 failed, 0 skipped
SMOKE TEST PASSED
```

Run twice against the running application and the real `ecomdemo-postgres` container; identical
both times. The script is unchanged in this phase's diff.

Worth being clear about the overlap this creates. The smoke test and `OrderApiIT` now prove
overlapping things — both race two checkouts for the last unit against real PostgreSQL. They are
not redundant: the smoke test proves it of a *deployed, running* application with an external
database and is the thing that will later run against a container image and a cluster; the
integration test proves it *in the build*, on every developer's machine and (from Phase 11) in
CI, with no application to start first.

## 5. Failure scenarios

This is not a resilience phase, but the checks that make the suite trustworthy were run:

| Scenario | Result |
|---|---|
| A failing IT | Build fails at `failsafe:verify` (§2b) |
| Containers left behind after a run | None. `docker ps -a --filter label=org.testcontainers=true` is empty after the build; only the user's own `ecomdemo-postgres` remains |
| Rows left behind in the dev database | None. `GET /api/products` returns 11 before and after (the ten seeded plus Phase 4's intentional persistence probe); the integration tests never touch port 5432 |
| The race arriving in sequence rather than in parallel | Harmless, and the assertion holds either way. When the two requests truly overlap the loser is refused by optimistic locking; when they happen to arrive one after the other the loser finds the shared cart already emptied. Both are a 409 and both leave exactly one order |
| Docker not running at all | ⚠️ not reproduced — see §9 |

Evidence that the race really did overlap, from the `verify` application log (`o-auto-1-exec-5`
is a Tomcat request thread, so this is the HTTP path, not a service-level call):

```
WARN 45292 --- [ecomdemo] [o-auto-1-exec-5] com.ecomdemo.order.OrderService :
  Checkout attempt 1 of 3 lost an optimistic lock ...
```

## 6. What was deliberately *not* done

- **H2 was not deleted.** Phase 5's comments predicted it would go this phase. It stays, and the
  comments were corrected to say why: the two suites answer different questions, and deleting H2
  would make the fast loop require Docker for every run. The stale comments in `pom.xml` and
  `application-test.properties` are part of this phase's diff.
- **Existing tests were not migrated.** `ConcurrentCheckoutTest`, `FlywayMigrationTest` and the
  other `@SpringBootTest` classes still run on H2 under Surefire. Moving them would have doubled
  the phase's scope and slowed the fast suite; the integration tests cover the same ground on
  PostgreSQL where it matters.
- **`withReuse(true)` was not enabled.** It keeps the container alive between runs, and with it
  the previous run's rows. Left off deliberately; the two lines that turn it on are documented in
  `PostgresContainerConfig`.
- **`testcontainers-junit-jupiter` was not added.** Its `@Testcontainers`/`@Container` extension
  is the alternative to letting Spring own the container, and Spring owns it here.

## 7. Startup log

Integration-test context startup, in order: Ryuk → PostgreSQL container → Hikari
(`EcomdemoItPool`) → Flyway V1–V4 → Hibernate `validate` → Tomcat on a random port.

```
Database JDBC URL [jdbc:postgresql://localhost:55963/test?loggerLevel=OFF]
Database version: 18.6
Isolation level: READ_COMMITTED [default READ_COMMITTED]
o.s.boot.tomcat.TomcatWebServer : Tomcat started on port 55972 (http)
com.ecomdemo.product.ProductApiIT : Started ProductApiIT in 12.135 seconds
```

Both ports are random and neither appears anywhere in the source: the JDBC url is read off the
container by `@ServiceConnection`, and the HTTP port is handed to `TestRestTemplate` by
`webEnvironment = RANDOM_PORT`.

The Mockito self-attach warning that Surefire silences with a `-javaagent` flag appeared in the
Failsafe JVM on its first run (spring-test's `MockitoTestExecutionListener` touches Mockito even
where nothing is mocked). The same `argLine` was added to the Failsafe configuration and the
warning is gone.

## 8. Cleanup

- Application stopped; `pgrep -f ecomdemo-0.0.1` returns nothing.
- No Testcontainers containers remain; `ecomdemo-postgres` (the dev database) is left running at
  schema v4, as it was before the phase.
- `GET /api/products` returns 11, unchanged from the end of Phase 6.
- `TemporarilyFailingIT` from §2b was deleted; `git status` is clean.

## 9. Manual verification needed

⚠️ **"Docker is not running" fails the build with a clear message.** Two attempts to fake it from
the build (`DOCKER_HOST` pointed at a non-existent socket, with and without
`TESTCONTAINERS_DOCKER_CLIENT_STRATEGY`) were both ignored — `~/.testcontainers.properties` pins
`UnixSocketClientProviderStrategy`, and the build found Docker anyway and passed. Rather than
report a check that did not run, here is how to see it for yourself:

1. Quit Docker Desktop entirely.
2. `./mvnw test` → still **BUILD SUCCESS**, 120 tests. This is the point of keeping H2.
3. `./mvnw verify` → **BUILD FAILURE** during `failsafe:integration-test`, with Testcontainers'
   "Could not find a valid Docker environment" in the stack trace.
4. Start Docker Desktop again, then `docker start ecomdemo-postgres` if the dev database does not
   come back up by itself.

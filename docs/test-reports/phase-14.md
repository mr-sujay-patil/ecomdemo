# Phase 14 Test Report: Batch Processing

- **Date:** 2026-09-22
- **Branch:** `feature/phase-14-spring-batch`
- **Toolchain:** Spring Boot 4.1.1 (`spring-boot-starter-batch`), Spring Batch 6.0.5,
  PostgreSQL 18.6, Redis 8, Testcontainers 2.0.5, JDK 21, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ all green. **One serious bug was found by asserting on rows in a table rather
  than on behaviour** — §4 — and it is the most useful part of this report.

## 1. Full regression — `./mvnw verify`

```
Tests run: 229, Failures: 0, Errors: 0, Skipped: 0     (surefire, was 190)
Tests run: 47,  Failures: 0, Errors: 0, Skipped: 0     (failsafe, was 38)
BUILD SUCCESS
```

New test classes:

| Class | Suite | What it covers |
|---|---|---|
| `ProductImportProcessorTest` | unit | every validation rule, and the upsert returning the same object |
| `RejectedRowRecorderTest` | unit | the error file: both skip callbacks, the header, CSV quoting |
| `BatchControllerTest` | slice | the HTTP contract, multipart binding, 403/401 for non-admins |
| `BatchJobRepositoryTest` | slice | **rows in `BATCH_JOB_*`** — see §4 |
| `SalesReportScheduleTest` | slice | the cron really fires the job, and six fields ≠ five |
| `ProductImportJobIT` | integration | 10,000 rows, skips, the error file, restart, 403 |
| `SalesReportJobIT` | integration | the report's contents against real orders |

No existing test was weakened. Three were extended: `FlywayMigrationTest` (V7 and V8) and
`OpenApiDocumentationTest` (the three new paths).

**One PostgreSQL and one Redis for the whole Failsafe run**, counted from the log:

```
grep -oE "Creating container for image: [a-z0-9:./-]+" | sort | uniq -c
   1 postgres:18-alpine
   1 redis:8-alpine
   1 testcontainers/ryuk:0.14.0
```

The two new `*IT` classes extend `IntegrationTest` and add no annotations of their own, so they
share the one context — the Phase 7 and Phase 13 lesson holding for a third time.

## 2. The fast suite still needs no Docker

```
./mvnw clean test  ->  Tests run: 229, Failures: 0, Errors: 0, Skipped: 0
"Creating container" lines: 0
```

The batch schema is plain SQL that H2 accepts, so `BatchJobRepositoryTest` and
`SalesReportScheduleTest` both run real jobs against H2 in the fast suite. Only the things that
are genuinely about PostgreSQL or about volume — the ten-thousand-row import, the restart — are
left to the `*IT` classes.

`SalesReportScheduleTest` costs a context of its own, because it has to override the cron through
`@TestPropertySource`. That is the honest price of testing a schedule and it is why only one test
class does it.

## 3. Phase acceptance — every "Done when" item

**"A 10,000-row import works with invalid rows skipped."**
`ProductImportJobIT.importsTenThousandRowsAndSkipsTheInvalidOnes` uploads a real multipart file of
10,000 good rows and 8 bad ones over HTTP and asserts:

```
status      COMPLETED
readCount   10007      (the 8th bad row fails in the READER and is never read)
writeCount  10000
skipCount   8
commitCount > 50       (chunks of 100, so ~100 transactions rather than one)
products    +10000
errorFile   9 lines: a header and one per rejected row
```

The error file is then read and checked to name the rules that were broken
(`price 'twelve' is not a number`, `name is required`, `stock_quantity '-2' must not be
negative`). The catalogue listing served afterwards contains the imported products, which proves
the cache eviction as well.

**"The report is generated on schedule."**
Split in two on purpose, because "on schedule" and "correct" are different claims:

- `SalesReportScheduleTest` sets the cron to every second and waits for the file. Nothing in the
  test calls the job; if `@Scheduled` were missing the file would never appear. It also asserts
  that Spring's six-field cron means what the property says, and that the five-field Unix form of
  "02:00 daily" would silently mean "the 2nd minute of every hour".
- `SalesReportJobIT` buys three orders through the real checkout and then reads the file the job
  wrote: the summary counts them, the best-seller table sums units and revenue per product, and
  the order of the rows is by units sold.

**Restartability demo.** `ProductImportJobIT.failsOnTheSkipLimitAndRestartsWhereItStopped`, and by
hand against the compose stack (§5).

## 4. The bug this phase would have shipped

**Spring Batch 6 defaults to an in-memory JobRepository, and nothing says so.**

`DefaultBatchConfiguration.jobRepository()` returns a `ResourcelessJobRepository`. Under it:

- jobs run and report correct counters;
- a `JobInstance` that has COMPLETED is still refused a second time;
- the restart endpoint still works within one process;
- **the `BATCH_*` tables that Flyway created stay completely empty.**

So every test in this phase passed, twice over, against a framework that had forgotten every run
the moment the JVM exited. What that silently removes is the whole reason to use Spring Batch
rather than a loop: restart after a crash (the saved reader position died with the process), the
guarantee that last night's job will not run twice, and any record to look an execution up in.

It was found by writing an assertion about **rows in a table** instead of about behaviour:

```java
assertThat(jdbc.queryForList(
    "SELECT ... FROM batch_job_execution WHERE job_execution_id = ?", execution.id()))
        .singleElement()...
```

which returned an empty list while everything else was green. The tell in the logs, in hindsight,
was that every execution in the whole run reported `id=1`.

The fix is `BatchConfig extends DefaultBatchConfiguration` with a
`JdbcJobRepositoryFactoryBean` — extending the class is what makes Boot's auto-configuration back
off, rather than adding a second `JobRepository` bean and fighting over the name.
`BatchJobRepositoryTest` now asserts both the rows and `isNotInstanceOf(ResourcelessJobRepository)`.

### Two smaller findings

**The deprecated chunk API.** `chunk(size, transactionManager)` builds the pre-6.0
implementation, which logs a deprecation note per step on every startup and goes away in Batch 7.
Moving to `chunk(size).transactionManager(...)` changed two observable things, both of which the
tests caught:

- `rollbackCount` is now **0** on a run with skips. The old model rolled the chunk back and
  replayed it item by item to find the skippable one; the new one handles it in place. Same
  outcome, much less work — and an assertion that used to prove the replay had happened now
  proves it no longer needs to.
- The failure of a skip limit arrives as `FatalStepExecutionException: Unable to process chunk`,
  with the useful sentence two links down the cause chain.

**So the API now reports the chain, not the outermost link:**

```
FatalStepExecutionException: Unable to process chunk; caused by SkipLimitExceededException:
Skip limit of '50' exceeded; caused by InvalidProductRowException: line 172: price 'NOPE' is not
a number
```

**`product.name` had no index.** The import looks every row up by name before deciding whether to
insert or update, so a 10,000-row file meant 10,000 sequential scans of a growing table. V8 adds
the index (not unique — see `docs/decisions.md`).

## 5. Running the whole application — `docker compose up -d --build`

All three containers healthy. Flyway applied V7 and V8 against PostgreSQL 18.6 on first start:

```
Migrating schema "public" to version "7 - batch job repository"
Migrating schema "public" to version "8 - index product name"
```

No deprecation notes in the startup log. The batch directory is a named volume
(`batch-data:/var/lib/ecomdemo/batch`) created in the Dockerfile as the runtime user — without
that, the first upload fails on `mkdir` inside a root-owned `/app`.

**The restart demo, by hand, against the running stack.** A 180-row file: 120 good rows then 60
with an unparseable price.

```
POST /api/admin/batch/product-import        -> 200
  status FAILED   written 100   skipped 50
  failure: ... SkipLimitExceededException: Skip limit of '50' exceeded ...
  input:   /var/lib/ecomdemo/batch/uploads/1591342e-...-restart-demo.csv

docker exec ecomdemo-app sh -c "sed -i 's/,NOPE,/,19.99,/' '<input>'"

POST /api/admin/batch/executions/5/restart  -> 200
  status COMPLETED   id 6   instanceId 5   read 80   written 80

GET /api/products  ->  180 demo products
```

Execution **6** against instance **5**: a second attempt at the same unit of work. It read 80
rows, not 180, because the last commit was at row 100 — the framework resumed rather than
starting again, and the 100 rows already imported were not touched.

The error file was readable inside the container throughout, including while the job was in its
failed state:

```
line,reason,original_line
122,"line 122: price 'NOPE' is not a number","Restart Demo bad 1,x,NOPE,5,DEMO"
```

The demo products were deleted afterwards; the catalogue is as it was.

## 6. End-to-end smoke test — `scripts/smoke-test.sh`

```
Summary: 156 passed, 0 failed, 0 skipped
SMOKE TEST PASSED
```

Was 136. The twenty new checks are in a "Batch processing" section and cover the phase's required
additions plus the authorization rules. **Zero skipped**, so every check really ran — including
the error-file check, which reads the file inside the container.

One existing check was updated rather than added to: the Flyway history assertion now expects
`V1-V8`.

## 7. Failure-scenario checks

| Scenario | Result |
|---|---|
| More bad rows than the skip limit | Job FAILED, the chunks already committed survive, the cause chain names the limit |
| Restarting a COMPLETED run | 409, refused before the framework is asked |
| Restarting an execution that is not an import | 409 |
| Looking up an execution that does not exist | 404 in the standard error shape |
| A CSV row with too few columns | Skipped in the reader, recorded in the error file with its line number |
| A CUSTOMER or anonymous caller importing | 403 / 401, and the service is never reached |
| A second sales report for a day already reported | 409 — the JobRepository refuses a completed instance |
| A day with no orders | COMPLETED, a report with zeroes and no rows |

## 8. What is NOT proven here

- **Restart across a process restart.** The demo restarts within one running application. The
  JobRepository is now on disk and the staged file is on a volume, so it should survive a
  container replacement — but that is an inference from where the state lives, not something a
  test in this phase runs. ⚠️
- **A second application instance.** `@Scheduled` fires in every JVM, so two instances would both
  start the nightly report and the second would be refused by the JobRepository. That refusal is
  tested; two instances actually racing are not.
- **Throughput.** 10,000 rows import in a few seconds on this machine. Nothing here is a
  benchmark, and the chunk size has not been tuned against anything.

## 9. Environment left behind

Docker Desktop running. `ecomdemo-app`, `ecomdemo-db` and `ecomdemo-cache` up and healthy, schema
at V8, catalogue back to its pre-test contents. The SonarQube stack is also up from Phase 12 —
`docker compose -f compose.sonar.yaml down` stops it. Nothing was written into the working tree:
the tests write under `target/`, and the application writes into the named volume.

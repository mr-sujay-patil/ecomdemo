# Phase 16 Test Report: Centralized Logging

- **Date:** 2026-09-22
- **Branch:** `feature/phase-16-logging`
- **Toolchain:** Spring Boot 4.1.1 (structured logging is native — **no new dependency**),
  Logback 1.5.x, Grafana Loki 3.7.8, Grafana Alloy v1.19.2, Grafana 12.3.1, Prometheus 3.7.3,
  PostgreSQL 18.6, Redis 8, Testcontainers 2.0.5, JDK 21, Docker 29.7.2, Compose v5.4.0
- **Result:** the phase's own work is ✅ green — full regression passes, every "Done when" item is
  verified, and the smoke test's 23 new checks all pass. **The smoke test as a whole is ❌ RED on
  one check that this phase did not cause and whose fix is Phase 13 scope** (§7). That is the open
  question for you, and nothing in this report is reported as passing that was not run.

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 268, Failures: 0, Errors: 0, Skipped: 0     (surefire, was 241)
Tests run: 70,  Failures: 0, Errors: 0, Skipped: 0     (failsafe, was 62)
BUILD SUCCESS  (1:04 min)
```

New test classes:

| Class | Suite | What it covers |
|---|---|---|
| `CorrelationIdFilterTest` | unit (+7) | generation, reuse of a caller's ID, replacement of an unsafe one, and the MDC lifecycle **including after an exception** |
| `RequestLogFilterTest` | unit (+6) | that the line carries method/path/status — and that it carries no token, no password, no query string; and that `/actuator` is skipped |
| `StructuredLoggingTest` | unit (+6) | a **real ECS document** encoded by Boot's own `StructuredLogEncoder`, plus a drift guard over `config.alloy` |
| `LoggingStackConfigTest` | unit (+8) | the four configuration files checked against each other |
| `CorrelationIdApiIT` | integration (+8) | the header over real HTTP on 200, 401, 403, 404 and `/actuator` |

No existing test was weakened, disabled or deleted. The fast suite is still Docker-free:

```
./mvnw clean test  ->  268 tests, 0 "Creating container" lines
```

## 2. Every "Done when" item

| Item | How it was verified |
|---|---|
| Structured JSON console logging | ✅ `StructuredLoggingTest` encodes and parses a real ECS document; the running container's stdout was read back and is JSON (`@timestamp`, `log.level`, `service.node.name`) |
| Correlation ID filter with MDC, returned in a response header | ✅ 7 unit + 8 integration checks, and 8 smoke checks against the running stack |
| Loki and Alloy in Compose, Loki as a Grafana data source | ✅ both containers run; smoke checks assert the datasource UID, the provisioned dashboard, **and that Grafana itself can proxy a query to Loki** |
| No sensitive data in logs | ✅ asserted twice: unit tests require absence from the line, and the smoke test searches the **live Loki** for this run's real password and bearer token |
| **All logs for one request can be found in Grafana by correlation ID** | ✅ smoke check `querying Loki by correlation ID returns this request's log lines`, plus a second asserting it finds **both** requests made under that ID. Verified again through Grafana's own datasource proxy |

## 3. Smoke test — `scripts/smoke-test.sh`

```
Summary: 227 passed, 0 failed, 0 skipped      (run of 2026-09-22, before §7 surfaced)
Summary: 226 passed, 1 failed, 0 skipped      (current — the §7 failure)
```

The 23 new checks, all passing in both runs:

```
Centralized logging
  PASS  every response carries an X-Correlation-Id header
  PASS  and the generated ID is safe to write into a log line
  PASS  two requests get two different IDs
  PASS  an ID supplied by the caller is reused, not replaced
  PASS  an unsafe ID is replaced rather than echoed
  PASS  a request refused with 401 still carries one
  PASS  a 404 carries one
  PASS  Actuator's endpoints carry one too
  PASS  querying Loki by correlation ID returns this request's log lines
  PASS  and it finds both requests made under that ID
  PASS  the ID is structured metadata, so it is not a label
  PASS  the log level is a Loki label
  PASS  the application's stream is labelled service_name=app
  PASS  the lines are the ECS JSON the application wrote, not text
  PASS  PostgreSQL and Redis are shipped as well, for the context an app log lacks
  PASS  the customer's password appears nowhere in the logs
  PASS  the admin's password appears nowhere in the logs
  PASS  no bearer token is written to the logs
  PASS  and no Authorization header is logged either
  PASS  Alloy is running the pipeline and has shipped entries to Loki
  PASS  Grafana provisioned the Loki datasource
  PASS  the logs dashboard is provisioned from the repository
  PASS  and Grafana itself can query Loki for that correlation ID
```

**The secrecy checks are not vacuous.** A "zero results" assertion passes just as happily against a
Loki that has nothing in it, so the same query mechanism was pointed at a string that *is* present:

```
{service_name="app"} |= `RequestLogFilter`   -> 344 lines
{service_name="app"} |= `smoke-test-password` ->   0 lines
{service_name="app"} |= `Bearer `             ->   0 lines
```

## 4. The application, run the way it runs at this phase

`docker compose up -d --build`, seven containers, all healthy. Startup log inspected as JSON:

```
WARN/ERROR lines at startup: 2
  ('WARN', 'org.springdoc.core.events.SpringDocAppInitializer', 'SpringDoc /v3/api-docs endpoint is enabled by default...')
  ('WARN', 'org.springdoc.core.events.SpringDocAppInitializer', 'SpringDoc /swagger-ui.html endpoint is enabled by default...')
```

Both pre-date this phase and are springdoc telling us what it is. No errors.

## 5. Failure scenario — Loki down

The phase's central design claim is that the application never waits for the log pipeline. Tested
rather than asserted:

```
docker stop ecomdemo-loki
curl -H 'X-Correlation-Id: outage-1790074614' .../api/products   -> HTTP 200 in 0s
curl .../actuator/health                                          -> HTTP 200
docker start ecomdemo-loki
query for correlation_id = outage-1790074614                      -> 1 line
```

The line written **while Loki was down** arrived after recovery: Alloy buffered it in memory and
retried. Nothing was lost, and nothing reached the application.

## 6. Mutation testing of the drift guards

Both guards in this phase are of the Phase 15 `DashboardMetricsTest` kind — they protect a contract
the compiler cannot see — so both were checked by breaking the thing they guard:

| Mutation | Result |
|---|---|
| `correlation_id = "correlation_id"` → `"correlationId"` in `config.alloy` | ❌ `StructuredLoggingTest.alloyExpressionsResolve` fails |
| derived-field regex `{8,64}` → `{100,200}` in `loki.yml` | ❌ `LoggingStackConfigTest.theDerivedFieldMatchesARealId` fails |

Both files were restored (`git status` clean) and the suite is green again: `Tests run: 14,
Failures: 0`.

## 7. ❌ The one failing check — a pre-existing bug this phase's run surfaced

```
FAIL  failed checkout did not touch stock
      expected: 0
      actual:   1
```

**It is not this phase's.** `git diff main -- src/main/java/com/ecomdemo/{order,product,cache}` is
empty: Phase 16 does not touch a single product, order or cache class.

**What it actually is.** Placing an order decrements `product.stock_quantity` in the database, and
evicts **neither** of the Phase 13 caches. `ProductService` evicts on create/update/delete; the
whole `order` package contains no cache eviction at all. So after a successful checkout,
`GET /api/products/{id}` serves the pre-order stock for up to the 300-second TTL.

Reproduced directly, outside the smoke test:

```
product 449
before  API: 4  DB: 4
order:  201
after   API: 4  DB: 2      <- the API is wrong for up to 300s
```

And in the smoke run's own data, the two caches disagreeing with each other:

```
GET /api/products         -> product 335, stockQuantity 0     (productList::all, correct)
GET /api/products/335     -> stockQuantity 1                  (product::335, stale)
SELECT ... WHERE id = 335 -> 0                                (the truth)
redis TTL product::335    -> 300
```

The smoke check compares the list's view with the single product's view, so it fails exactly when
the product it picks has a stale entry — which is why it passed three times earlier in the day and
fails now that the data has drifted. **The check is right and the application is wrong.**

**Why it is not fixed here.** Hard rule 7: implement only the current phase's scope. The fix
belongs to Phase 13's caching and needs a decision about *where* — evicting from `OrderService`
couples ordering to caching, and the eviction must happen after the commit, not inside it, for the
reason `ProductUpsertWriter` documents in Phase 14. Hard rule 8 forbids the other way out: the
check will not be weakened or removed to get green.

**Options, for you to choose:**

1. Fix it inside this PR as a bounded, documented exception to rule 7.
2. Merge Phase 16, then fix it in a `fix/` branch and PR of its own before Phase 17.
3. Accept it as a known defect and carry it in the follow-ups.

Recommendation: **(2)**. It keeps this phase's diff about logging, gives the fix its own review and
its own test, and it is a small change — but it should not ride along inside a logging PR.

## 8. ⚠️ Still outstanding from Phase 15

The Grafana **render** has still never been looked at by human eyes — Chrome's site permissions
block `localhost:3000` for browser automation in this environment. Everything behind the pixels is
verified (every Phase 15 panel query returns data; the Phase 16 datasource, dashboard and a
proxied query are all asserted by the smoke test), but the two dashboards' appearance is a manual
step. Steps are in `docs/test-reports/phase-15.md` §8; for this phase, open
<http://localhost:3000>, pick **EcomDemo Logs**, and paste a correlation ID from any response
header into the textbox at the top.

## 9. Environment left behind

Docker Desktop running. Seven application containers up and healthy (`app`, `db`, `cache`,
`prometheus`, `grafana`, `loki`, `alloy`) plus the SonarQube stack from Phase 12. Schema V8 —
this phase adds no migration. `.env` holds a real `JWT_SECRET` and is gitignored. No stray Java
processes.

**One environment trap worth writing down**, hit during the Phase 15 merge verification an hour
earlier: checking out a branch that does not contain a bind-mounted config directory **deletes it
and recreates it with a new inode**, and a running container keeps the old one — so Grafana saw an
empty `/etc/grafana/provisioning`. The fix is `docker compose up -d --force-recreate <service>`
after switching branches, not a config change.

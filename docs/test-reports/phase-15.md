# Phase 15 Test Report: Metrics & Monitoring

- **Date:** 2026-09-22
- **Branch:** `feature/phase-15-metrics`
- **Toolchain:** Spring Boot 4.1.1 (`spring-boot-starter-actuator`), Micrometer 1.17.1,
  `micrometer-registry-prometheus`, Prometheus 3.7.3, Grafana 12.3.1, PostgreSQL 18.6, Redis 8,
  Testcontainers 2.0.5, JDK 21, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ all green, with **one ⚠️ item that genuinely needs your eyes** (§8) and **one
  documented claim that the failure test corrected** (§7).

## 1. Full regression — `./mvnw clean verify`

```
Tests run: 241, Failures: 0, Errors: 0, Skipped: 0     (surefire, was 229)
Tests run: 62,  Failures: 0, Errors: 0, Skipped: 0     (failsafe, was 47)
BUILD SUCCESS
```

New and extended test classes:

| Class | Suite | What it covers |
|---|---|---|
| `OrderServiceTest` → nested `CheckoutMeters` | unit (+7) | outcome classification: which of the five tags each exception becomes, and that a retried checkout is **one** observation |
| `DashboardMetricsTest` | unit (+5) | the shipped dashboard JSON and alert rules only name meters the application registers |
| `ActuatorApiIT` | integration (+15) | the endpoints, the two health groups, authorization, and the meters **as they appear in the scrape text** |

No existing test was weakened, disabled or deleted. `OrderServiceTest` gained a real
`SimpleMeterRegistry` rather than a mocked `CheckoutMetrics`, because mocking would verify that a
method was called while the registry verifies what the meter actually holds — which is the only
thing a dashboard can read.

**One PostgreSQL and one Redis for the whole Failsafe run**, unchanged — the new IT joined the
shared context instead of splitting it:

```
   1 postgres:18-alpine
   1 redis:8-alpine
   1 testcontainers/ryuk:0.14.0     (the Testcontainers reaper, not a data container)
```

## 2. The fast suite still needs no Docker

```
$ ./mvnw clean test
Tests run: 241, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

$ grep -c "Creating container" fast.log
0
```

`DashboardMetricsTest` reads two files off disk and builds a `SimpleMeterRegistry`, so it stays in
the fast suite where it belongs — a drift guard that only ran in the slow suite would be found out
too late to be useful.

## 3. The drift guard was verified by mutation, not by passing

A test that asserts "every metric the dashboard names exists" is worthless if it silently extracts
nothing. So it was broken deliberately, twice:

| Mutation | Result |
|---|---|
| `orders_placed_total` → `orders_completed_total` in `docker/grafana/dashboards/ecomdemo.json` | ❌ `Tests run: 5, Failures: 1` |
| `checkout_duration_seconds_count` → `checkout_time_seconds_count` in `docker/prometheus/alerts.yml` | ❌ `Tests run: 5, Failures: 1` |
| both files restored | ✅ `Tests run: 5, Failures: 0` |

The test also asserts its own extraction is non-empty, so it cannot start passing by quietly
finding nothing.

## 4. Phase acceptance — every "Done when" item

| Item | Status | How it was verified |
|---|---|---|
| Endpoints: health (liveness + readiness groups), info, metrics, prometheus | ✅ | `ActuatorApiIT` (5 tests) + 18 smoke checks; `/actuator/env` and `/actuator/heapdump` confirmed 404 **even for an ADMIN** |
| Business metrics: `orders.placed`, `order.value`, checkout timer | ✅ | `OrderServiceTest.CheckoutMeters` (7), `ActuatorApiIT` (5), 14 smoke checks asserting exact deltas |
| Prometheus + Grafana in Compose, provisioned dashboard and one alert rule | ✅ | `promtool check config` on both files; 10 smoke checks against the live Prometheus and Grafana APIs |
| **The dashboard shows live traffic and business metrics** | ✅ data / ⚠️ render | see §5 and §8 |

## 5. "The dashboard shows live traffic" — how this was actually proven

Grafana could not be opened in a browser from this environment (§8), so the claim was tested at the
layer that decides whether a panel draws anything: **every one of the dashboard's own queries was
extracted from the JSON and run through the Prometheus API**, with `$__rate_interval`, `$__range`
and `$application` substituted as Grafana would.

Traffic first: a throwaway generator placed **105 orders** over several minutes, with deliberate
empty-cart failures mixed in so the `outcome` tag had more than one value.

```
16 panel queries returning data, 0 empty or failing
```

| Panel | Query returned |
|---|---|
| Orders placed / min | `26.79` |
| Revenue (selected range) | `2655.19` |
| Average order value | `19.81` (probe product priced 19.99) |
| Failed checkouts | `0.217` — the deliberate empty-cart share |
| Checkout rate by outcome | 5 series, one per tag value |
| Checkout latency p50 / p95 / p99 | `0.0089` / `0.0252` / `0.0484` s |
| HTTP requests by status | 9 series |
| HTTP p95 by endpoint | 5 series |
| JVM heap used | 3 series (Eden, Survivor, Old Gen) |
| Database connections | active / idle / pending |
| CPU usage | process `0.011`, system `0.082` |

**The alert rule was checked the same way, and then checked harder.** Its own expression evaluates
to `0` — correct, there was no lock contention — which on its own proves nothing about whether the
rule *can* fire. So the identical expression was run with `empty_cart` substituted for `conflict`,
against the same live data:

```
conflict  ratio = 0                    (rule correctly quiet)
empty_cart ratio = 0.2117
empty_cart ratio > 0.05  ->  1 row returned   (this shape of rule does fire)
```

## 6. Running the whole application — `docker compose up -d --build`

```
ecomdemo-db          Up (healthy)
ecomdemo-cache       Up (healthy)
ecomdemo-app         Up (healthy)      <- now via /actuator/health/readiness
ecomdemo-prometheus  Up (healthy)
ecomdemo-grafana     Up (healthy)

Started EcomdemoApplication in 6.103 seconds
```

No `ERROR` lines. The only `WARN`s are pre-existing: SpringDoc's two "enabled by default" notices
from Phase 3, and `RejectedRowRecorder` reporting the deliberately malformed CSV rows the Phase 14
smoke checks feed it.

Prometheus targets and rules, read from its API:

```
ecomdemo    http://app:8080/actuator/prometheus   up
prometheus  http://localhost:9090/metrics         up
ecomdemo-checkout -> CheckoutConflictRateHigh  inactive  health: ok
```

## 7. Failure-scenario checks — and the claim they corrected

This is the phase's most consequential configuration, so it was tested by stopping things rather
than by reading the properties file.

| Scenario | `/actuator/health` | readiness | liveness | Catalogue |
|---|---|---|---|---|
| baseline | UP | UP | UP | 200 |
| **Redis stopped** | **DOWN** | **UP** | UP | **200** |
| Redis restarted | UP | UP | UP | 200 |
| **PostgreSQL stopped** | DOWN | **DOWN (503)** | **UP** | — |
| PostgreSQL restarted | UP | UP | UP | 200 |

The Redis row is the one that matters: plain `/actuator/health` goes DOWN, readiness stays **UP**,
and the shop keeps serving. Had Redis been left in the readiness group — the default behaviour —
every instance would have been pulled out of the load balancer over a cache outage the application
demonstrably survives.

**What this test corrected.** The README had been written claiming readiness answers
`{"status":"OUT_OF_SERVICE"}` when the database is gone. It does not. It answers
`{"status":"DOWN"}` with HTTP 503 — and it takes **10.07 seconds** to do so, because the `db`
indicator waits out the PostgreSQL driver's `connectTimeout` first. A health check is only as fast
as its slowest indicator, and for the first ten seconds an unreachable database and a slow one are
the same observation. The README was rewritten to the measured behaviour.

That also interacts with the container health check, whose `--timeout=3s` is shorter than the
probe's worst case: with the database gone, `wget` gives up before readiness answers, so the check
fails by **timeout** rather than by reading the 503. After `--retries=5` at `--interval=10s` Docker
reached the right verdict anyway —

```
$ docker inspect -f '{{.State.Health.Status}}' ecomdemo-app
unhealthy
```

— but by a route worth being clear-eyed about, and the README now says so rather than implying the
503 is what Docker read.

## 8. ⚠️ Needs manual verification: how the dashboard looks

**Chrome's site permissions blocked `localhost:3000`**, so no screenshot of Grafana was taken.
Everything the dashboard *reads* is verified in §5; what is unverified is purely the render —
whether the panels are legible, the units sensible and the layout sane.

Two minutes, with the stack already up:

```bash
open http://localhost:3000        # admin / admin; the dashboard is the home page
```

Then generate some traffic so the graphs have something in them:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"asha","password":"correct-horse-battery-staple"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')

for i in $(seq 1 20); do
  curl -s -o /dev/null -X POST localhost:8080/api/cart/items -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"productId":1,"quantity":1}'
  curl -s -o /dev/null -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN"
  sleep 1
done
```

Worth looking at specifically:

1. **Orders placed / min** and **Revenue** move as the loop runs (the top row, 10s refresh).
2. **Checkout rate by outcome** is stacked and shows `placed`; check out an empty cart and a second
   colour appears for `empty_cart`.
3. **Checkout latency** draws three quantile lines rather than "No data" — that is the histogram
   buckets working.
4. **Database connections** shows `pending` flat at zero. That is the saturation signal.

## 9. End-to-end smoke test — `scripts/smoke-test.sh`

```
Summary: 204 passed, 0 failed, 0 skipped        (was 156)
SMOKE TEST PASSED
```

48 new checks in a "Metrics and monitoring" section. The ones worth naming, because they assert a
number rather than a status code:

- `orders_placed_total` incremented by **exactly 1**, and `order_value_sum` grew by **exactly the
  order's own total** — not a rounded or averaged version of it.
- A checkout of an **empty** cart moved `outcome="empty_cart"` by 1 and left `outcome="conflict"`
  — the series the alert rule watches — untouched.
- Every meter was present **at zero** before the first order, all five `outcome` tags included.
- `grep -c 'uri="/actuator' = 0`: the scrape does not appear in its own HTTP timers.
- `uri="/api/orders/{id}"` is present and `uri="/api/orders/47"` is not — URI templates, not paths.

The Prometheus and Grafana checks `skip()` rather than pass when those services are unreachable, so
a run against a bare application cannot report monitoring as verified when nothing was there to
verify. In this run nothing was skipped.

## 10. What is NOT proven here

- **The dashboard's appearance.** §8. Data verified, render not.
- **The alert firing end to end.** The rule's shape was proven to fire against live data with a
  substituted label (§5), and `for: 10m` was read from the file — but no run actually sustained >5%
  lock contention for ten minutes, and there is no Alertmanager, so nothing was ever *delivered*
  anywhere. Alertmanager is not in this phase's scope.
- **Multi-instance aggregation.** The `application` common tag and the histogram-over-percentiles
  choice both exist for the day there are two instances. There is one, so the thing they protect
  against has not happened yet.
- **Cardinality under real traffic.** The tags are bounded by an enum and by URI templates, and
  both are asserted — but no test counts total series against a budget.
- **Retention and disk.** `--storage.tsdb.retention.time=15d` is set and never reached.

## 11. Environment left behind

Docker Desktop running. Full stack up and healthy: `ecomdemo-app`, `ecomdemo-db`, `ecomdemo-cache`,
`ecomdemo-prometheus`, `ecomdemo-grafana`, schema v8. SonarQube stack also still up from Phase 12
(`docker compose -f compose.sonar.yaml down` to reclaim the memory). The load generator's probe
product was deleted and the catalogue is back to 25 products. `.env` holds a real `JWT_SECRET` and
is gitignored. No stray Java processes.

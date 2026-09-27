# Phase 22 Test Report: Resilience

- **Date:** 2026-09-27
- **Branch:** `feature/phase-22-resilience`
- **Machine:** the WSL2 workstation (24 CPU, 30 GB) — the first phase developed and verified there.
  Every result below was produced by Claude Code on that machine.
- **Toolchain:** Spring Boot 4.1.1, **Resilience4j 2.4.0 (`resilience4j-spring-boot4`)**, Spring Cloud
  2025.1.3, Testcontainers 2.0.5, JDK 21 (Temurin 21.0.12), Docker 29.8.0
- **Result:** ✅ green. `./mvnw clean verify` BUILD SUCCESS; smoke **313 passed / 0 failed / 0 skipped**
  from a cold stack — with one earlier cold run that was not clean, reported in §5.

## 0. Read this first: the first design could not meet its own budget

The resilience policy was written, unit-tested, mutation-checked and green — and the first live run
measured what it had assumed. Stopping catalog-service does **not** make calls to it fail at once:

| Run | Policy | Fail-fast request | What each failed attempt cost |
|---|---|---|---|
| 1 | 3 attempts, 500 ms connect, 1 s read | **1769 ms** (passed a 2 s check by 231 ms) | 0.50 s — the connect timeout |
| 2 | 3 attempts, 250 ms connect, 1 s read | **2561 ms** — FAILED | 1.00 s — the **read** timeout |
| 3+ | **2 attempts, 250 ms connect, 500 ms read** | 607–669 ms | 0.25 s (≤ 0.5 s worst case) |

Two different mechanisms, both measured:

- **The JVM caches the service's IP** (30 s by default). After the container stops, the application keeps
  connecting to its old address, and on a Docker bridge a SYN to that address goes **unanswered**, not
  refused. The attempt ends at the connect timeout.
- **Once the cache expires, resolving the name hangs.** From inside the app container, resolving the
  stopped `catalog-service` took **11.8 s** (three runs: 11833, 11799, 11819 ms) before NXDOMAIN —
  Docker's embedded DNS forwards the unknown name upstream and waits. The JDK HttpClient's connect
  timeout does **not** cover name resolution, so the attempt ends at the read timeout, which bounds the
  whole exchange.

So the real per-attempt cost of an outage is the **read** timeout, and **timeouts compose**:

    worst case = max-attempts × read-timeout + backoff = 2 × 500 ms + ≤ 150 ms = 1.15 s

The first design was 3 × 1 s + 450 ms = 3.45 s — a policy that could never meet a 2 s budget however
well each piece was tested. `ResilientCatalogTest.theWorstCaseFitsTheBudget` now recomputes that sum from
the shipped `application.properties`; putting the 1 s read timeout back fails it with `2.15S`.

## 1. Full regression

    common                12 (+2)          inventory-service     34
    catalog-service       41 + 12 ITs      customer-service      43 + 8 ITs
    notification-service   8 +  3 ITs      gateway-service        7 + 15 ITs
    ecomdemo-app         231 (+13) + 53 ITs
    BUILD SUCCESS in 2:03 (stack down)

    Baseline on this machine BEFORE any change: identical counts minus the 15 new tests, 2:28.

⚠️ **One verify run took 86 minutes** and passed. `CacheApiIT` (catalog-service) accounted for 5077 s,
but its six tests took 1.5 s: the time was two stalls in class setup — the Docker daemon taking
**30 min 20 s** to start a Postgres container, then the JVM's main thread silent for **54 min** between
Flyway and Hibernate, with total CPU unchanged at ~4.5 min. The kernel log shows Hyper-V's TimeSync
driver re-initialising at the second the JVM resumed (17:22:15 / 17:22:16) — the signature of the WSL2
VM being paused and resumed, most likely the Windows host sleeping. The earlier gap has the same shape
but no matching kernel event; consistent with a pause, not proven. An immediate rerun: **2:03**,
catalog-service 19 s. Nothing in catalog-service changed in this phase.

## 2. Phase acceptance — every Done-when item

**Done when: stopping a downstream service degrades the system gracefully.** ✅, by the smoke test
against the real stack (`docker stop ecomdemo-catalog-service`):

| Check | Result |
|---|---|
| Add-to-cart with catalog-service DOWN | **503**, not 500, in **607 ms** (budget 2 s) |
| The body | `"The product catalogue did not respond. Please try again shortly."` |
| After repeated failures | breaker **OPEN** (`resilience4j_circuitbreaker_state{state="open"} 1`) |
| With the circuit open | 503 in **20 ms**, "temporarily unavailable", no call made |
| `Retry-After` | **10** — exactly how long the breaker stays open |
| Checkout of a cart filled earlier | **201** — it never needed the catalogue |
| Order history | 200 |
| The application's own health | 200 — an open breaker is deliberately not a health failure |
| `docker start` — no action on the app | add-to-cart **200 after 10 s**; breaker CLOSED |

**Deviation from the phase file, deliberately.** It says "stop catalog-service: *checkout* fails fast
with 503". Since Phase 20c checkout does not call catalog-service — it charges the price snapshotted into
the cart and talks only to inventory-service. The order → catalog call a shopper makes is **add-to-cart**.
The smoke test therefore asserts that add-to-cart fails fast **and that checkout succeeds**, which is the
stronger form of "degrades gracefully". Making checkout call the catalogue just to watch it fail would
add coupling to the most important path to satisfy a test. Recorded in `docs/decisions.md`.

| Checklist item | Evidence |
|---|---|
| Circuit breaker, retry, timeout on order → catalog, with fallbacks (a clear 503) | `ResilientCatalogTest` (11), `CatalogClientTimeoutTest` (2), smoke §Resilience (18) |
| A bulkhead | `ResilientCatalogTest.refusesTheExcess`: 20 blocked calls, the 21st refused in < 50 ms, not counted against catalog-service |
| Resilience metrics in Grafana | `ecomdemo-resilience.json`, provisioned (Grafana API: "EcomDemo Resilience", folder EcomDemo, 9 panels); every panel query run against live Prometheus, all `success` with data |
| A failure demo | `scripts/failure-demo.sh`, run end to end (§4) |

## 3. The tests, and the mutations they were checked against

Every new test was broken on purpose once, to prove it can fail:

| Mutation | Caught by |
|---|---|
| Remove `requestFactory.setReadTimeout(...)` | `CatalogClientTimeoutTest` — "Expecting code to raise a throwable" |
| Delete `record-exceptions` and `retry-exceptions` from the properties | 4 of `ResilientCatalogTest` — a 404 opening the breaker, a 400 retried, the bulkhead's refusals counted |
| Rename a metric in the dashboard | `DashboardMetricsTest.everyMetricTheResilienceDashboardQueries…` |
| Filter panels on `name="catalog-service"` | `DashboardMetricsTest.theResilienceDashboardSelectsTheInstance…` |
| Read timeout back to 1 s | `ResilientCatalogTest.theWorstCaseFitsTheBudget` — `2.15S` |

`ResilientCatalogTest` runs Resilience4j's **own** Boot auto-configuration fed the `resilience4j.*` lines
read out of `application.properties`, so it tests the shipped policy rather than a copy of it.

**A dependency-scope trap avoided, and guarded.** `resilience4j-micrometer` is declared directly (the
dashboard test compiles against it). Declared at `test` scope it would have overridden its transitive
`runtime` scope and **left the packaged application** — no resilience series in production, every test
green. That is the Phase 20c `spring-boot-restclient` defect in another jar. The smoke test's first
Resilience check scrapes the **container's** `/actuator/prometheus`, which is the only place that defect
would show.

## 4. The failure demo, as it ran

    1. catalog-service is UP
      add to cart                        200     30 ms   circuit: CLOSED
    2. docker stop ecomdemo-catalog-service
      add to cart (attempt 1)            503    622 ms   circuit: CLOSED    The product catalogue did not respond…
      add to cart (attempt 2)            503    333 ms   circuit: OPEN      The product catalogue is temporarily unavailable…
      add to cart (attempt 3)            503     19 ms   circuit: OPEN      …
    4. what does not need the catalogue still works
      checkout (cart filled in step 1)   201     37 ms   circuit: OPEN
    5. docker start ecomdemo-catalog-service - and nobody touches the application
      add to cart (+10s)                 503     18 ms   circuit: OPEN
      add to cart (+13s)                 200    221 ms   circuit: HALF_OPEN
      add to cart (+19s)                 200     29 ms   circuit: CLOSED

## 5. ⚠️ Smoke runs, all of them

| Run | Stack | Result |
|---|---|---|
| 1 | warm, first policy | 311 / 0 — fail-fast 1769 ms (§0) |
| 2 | warm, 1 s read | 312 / **1** — fail-fast 2561 ms (§0) |
| 3 | warm, final policy | 313 / 0 |
| 4 | cold | **invalid** — Prometheus could not bind 9090 (below); 299 / 0 / **3 skipped** |
| 5 | cold, Prometheus on 19090 | 310 / **3** — see below |
| 6 | cold, Prometheus on 19090 | **313 / 0 / 0** |
| `main` | cold, Prometheus on 19090 | 295 / 0 / 0 |

**Run 4: a Windows host port reservation.** `docker compose up` failed with *"ports are not available:
exposing port TCP 0.0.0.0:9090 … /forwards/expose returned unexpected status: 500"*. Nothing listened on
9090 inside WSL. `netsh interface ipv4 show excludedportrange protocol=tcp` on the Windows host lists
**9022–9121** as reserved (Hyper-V / WinNAT dynamic exclusions, which can move when the host resumes).
Runs 5, 6 and `main` used `PROMETHEUS_PORT=19090 PROMETHEUS_URL=http://localhost:19090` — both already
configurable. **Not a project change; a manual step for the user (below).**

**Run 5: three intermittent failures in Phase 15/17 checks, not in this phase's code.**

- *"the dashboard's orders-per-minute query returns data"* — ran about a minute after Prometheus started.
- *"and it is still notified, so the poison never blocked the partition"* and *"the same event delivered
  twice writes ONE notification"* — orders 50, 51 and 53 were each notified exactly once, but all at
  **17:35:00.8**, seven seconds after the smoke test's own Kafka-outage section restarted the broker
  (17:34:53), while orders on other partitions (52, 54–56) were notified promptly. On that cold start the
  notification consumer sat behind the poison message on one partition until a broker bounce forced a
  rebalance. No notification was lost; one partition was stalled for about three minutes.

The same code passed from an identical cold start in run 6, and `main` passed cold once. Phase 22
changes nothing in messaging, notification-service or Prometheus, and the Resilience section runs after
all three checks. **Intermittent, pre-existing, and carried** — not fixed here (out of scope), recorded in
`CURRENT.md`. The poison-message stall is a real behaviour worth a follow-up: the check that exists to
catch it caught it once in three cold runs on this machine.

## 6. Environment

Seventeen containers. Memory after the cold smoke run: **2864 MiB**. Not comparable with Phase 21's
1387 MiB — that was the Mac's 3916 MiB Docker VM, where the unbounded containers (Kafka, Grafana,
Prometheus) had far less room to grow. The capped services on this machine:

    ecomdemo-app                 457 / 768 MiB   59 %
    ecomdemo-catalog-service     354 / 384 MiB   92 %
    ecomdemo-gateway-service     249 / 320 MiB   78 %
    ecomdemo-notification-service 312 / 320 MiB  97 %   ← no OOM kill (checked: OOMKilled=false)

## 7. Manual steps for you

1. **Look at the dashboard** (⚠️ how it *looks* cannot be verified automatically):
   `docker compose up --build --wait`, open http://localhost:3000/d/ecomdemo-resilience, then run
   `./scripts/failure-demo.sh`. Expect the state timeline to go green → red → orange → green.
2. **The 9090 reservation**, if `docker compose up` fails on Prometheus: either set
   `PROMETHEUS_PORT=19090` in your `.env`, or in an **admin** PowerShell run `net stop winnat` then
   `net start winnat` to release the dynamic exclusions (it briefly interrupts WSL/Docker networking).

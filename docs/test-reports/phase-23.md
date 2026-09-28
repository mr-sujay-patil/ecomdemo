# Phase 23 Test Report: Distributed Tracing

- **Date:** 2026-09-28
- **Branch:** `feature/phase-23-tracing`
- **Machine:** the WSL2 workstation. Every result below was produced by Claude Code on that machine.
- **Toolchain:** Spring Boot 4.1.1 (`spring-boot-starter-opentelemetry`), Spring Framework 7.0.9,
  **Grafana Tempo 3.0.3**, JDK 21, Docker 29.8.0
- **Result:** ✅ green. `./mvnw clean verify` BUILD SUCCESS (483 tests, 0 failed, 0 skipped); smoke
  **327 passed / 0 failed / 0 skipped** on three cold runs of the final code. Two earlier cold runs
  each failed one check. Both were test-side defects, fixed in the script, and are reported in §4.

## 1. Full regression

    common                12               inventory-service     34
    catalog-service       41 + 12 ITs      customer-service      43 + 8 ITs
    notification-service  10 +  4 ITs      gateway-service        7 + 15 ITs
    ecomdemo-app         244 + 53 ITs
    BUILD SUCCESS in 2:10 (stack up, allowed on this machine)

The first full `verify` of this phase failed 3 tests in `ecomdemo-app`. Each was stale, not a defect.
They failed identically with the latest change stashed:

- `FlywayMigrationTest` (2) expected V1–V16; this phase adds **V17** (`outbox_event.trace_parent`).
- `LoggingStackConfigTest.thePropertyReadsTheEnvironmentVariable` looked for `LOG_FORMAT` in the app's
  `application.properties`; the line moved into the shared `ecomdemo-observability.properties`. The
  test now checks both links: the shared file sets it, **and** the app imports the file.

## 2. Phase acceptance: the Done-when item

**Done when: one checkout shows as a single end-to-end trace.** ✅ This is checked by the smoke test
against the real stack. The script sends a checkout with its **own** sampled `traceparent`, then
queries Tempo for that trace id:

    PASS  Tempo has the checkout's trace, under the id the client chose
    PASS  with spans from at least 4 services (catalog-service,ecomdemo,gateway-service,inventory-service,notification-service)
    PASS  the gateway CONTINUED the caller's trace: its server span's parent is the span id sent
    PASS  the application's span is in it (gateway -> app over HTTP)
    PASS  inventory-service's reservation is in it (app -> inventory over HTTP)
    PASS  the outbox relay's span is in it, carrying the order id (the async hop)
    PASS  notification-service's consumer span is in it (app -> Kafka -> notification)
    PASS  a request whose traceparent said NOT sampled left no trace (parent-based sampling)
    PASS  Loki finds that trace id on log lines from at least 3 services
    PASS  Grafana has the Tempo datasource
    PASS  and can fetch the checkout's trace through it
    PASS  Loki's trace_id derived field links to Tempo

This is one checkout's trace as Tempo returned it, from a run on this machine. Every span shares one
trace id, and each parent id is the span before it:

    gateway-service      http post                              parent = the client's span (not exported)
    gateway-service        HTTP POST                            -> ecomdemo
    ecomdemo                 http post /api/orders
    ecomdemo                   http get  -> inventory-service   http get /api/inventory/{productId}
    ecomdemo                   http post -> inventory-service   http post /api/inventory/{productId}/reserve
    inventory-service              inventory.stock-changed send -> catalog-service  inventory.stock-changed process
    ecomdemo                   outbox relay                     (0.9 s later, on the scheduler thread)
    ecomdemo                     orders.placed send -> notification-service  orders.placed process

| Checklist item | Evidence |
|---|---|
| Tracing in all services, over HTTP and Kafka | smoke (above); `OutboxTracingTest` (6, real OTel SDK) |
| Tempo in Compose | `tempo` container, `GET /ready` checked by the smoke test |
| Trace IDs in logs, linked from Loki to Tempo | smoke: Loki finds the trace id in ≥3 services; derived field → Tempo. Gateway: by hand (§3) |
| A sampling configuration | smoke: an unsampled `traceparent` leaves no trace; `TRACING_SAMPLING_PROBABILITY` |

## 3. Found in this round, and fixed

**The review: `tasks.scheduled` was switched off for every job.** `management.observations.enable.tasks.scheduled=false`
had been added to quiet the outbox relay's once-a-second tick. Every `@Scheduled` method shares that
observation name, so the property also removed the outbox cleanup sweep's and the nightly sales report's
span **and** run-time metric. It now uses `OutboxObservationConfig`, an `ObservationPredicate` that checks
the task's class and drops only `OutboxRelay`. `OutboxObservationConfigTest` (4) runs it inside a real
`ObservationRegistry`. **Mutation:** pointing it at `OutboxCleanupJob` instead fails 2 of the 4.

**The gateway's log lines had no `traceId`.** The gateway's own code writes no logs, so the lines it
writes during a request come from the framework. The one that matters is Boot's ERROR for a 5xx.
Test: stop catalog-service, send `GET /api/products` with a chosen `traceparent`, and read the line.

| | gateway's ERROR line (`500 Server Error for HTTP GET "/api/products"`) |
|---|---|
| before | fields `@timestamp, ecs, error, log, message, process, service, tags`. **No `traceId`** |
| after `spring.reactor.context-propagation: auto` | `traceId` = the id sent, plus `spanId`; Loki query `{service_name="gateway-service"} \| trace_id="<id>"` returns it |

⚠️ This is not a smoke check, because it needs a service stopped. See §6.

## 4. ⚠️ Smoke runs, all of them

| Run | Stack | Result |
|---|---|---|
| 1 | cold | 325 / **1**: `flyway_schema_history shows V1-V16`. Stale check in the script (V17 exists) |
| 2 | cold | 326 / **1**: "the gateway CONTINUED the caller's trace". See below |
| 3–5 | cold | **327 / 0 / 0** each. Before the gateway fix and the port change |
| 6 | cold, Prometheus on its new default port | **327 / 0 / 0** |
| 7, 8 | cold, final code | **327 / 0 / 0** each |

**Run 2: the check ran before the gateway's spans had arrived.** The script polled Tempo until
notification-service's spans arrived, then asserted on the gateway's. Each service exports on its own
5 s timer, and in this run notification-service's batch reached Tempo first. Looked up afterwards, the
same trace held the gateway's server span with the expected structure: its parent was the unexported
client span. The poll now waits for **both** the gateway's and notification-service's spans. Runs 3–8
passed with that change.

A cold run is `docker compose down` then `docker compose up --build --wait`. All six services' logs
from start-up to the start of run 8 contain **no ERROR lines**. The stack was stopped after testing.

## 5. Environment

**Prometheus moved to host port 19090** (user's decision, `compose.yaml`). On 2026-09-28 Windows reserved
**9014–9113** for Hyper-V/WinNAT, and the first `up` failed with *"ports are not available: exposing
port TCP 0.0.0.0:9090"*. The smoke test, `.env.example`, the README and the environment notes follow the
new port. Inside the network Prometheus is still `prometheus:9090`. Run 6 exported nothing and found
Prometheus on 19090 by default.

Eighteen containers (Tempo is new). Memory after a cold smoke run: **3589 MiB** in total. Tempo used
61 MiB. The six JVMs used 287–474 MiB each, all under half of their limits.

## 6. Manual steps for you

1. **Look at a trace in Grafana** (⚠️ how it *looks* cannot be verified automatically):
   `docker compose up --build --wait`, place an order (or run `scripts/smoke-test.sh`), open
   http://localhost:3000 → *Explore* → **Tempo** → *Search*, and open the newest `gateway-service
   http post` trace. Expect the tree in §2, with the Kafka half about a second to the right.
2. **Follow the links both ways.** On a span, click *Logs for this span*: Loki lines from several
   services. In Loki, expand a JSON line with a `traceId`: the *View trace* link opens the same trace.
3. **Optional: the gateway's trace id.** `docker compose stop catalog-service`, then
   `curl http://localhost:8080/api/products -H "traceparent: 00-$(openssl rand -hex 16)-$(openssl rand -hex 8)-01"`
   and look for the gateway's ERROR line in Loki. It carries that `traceId`. Afterwards run
   `docker compose start catalog-service`.

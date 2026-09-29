# Phase 30 Test Report: Performance Testing (Gatling)

- **Date:** 2026-09-29
- **Branch:** `feature/phase-30-gatling`
- **Machine:** the WSL2 workstation (24 cores, 30 GB). Claude Code produced every result below on
  that machine. Gatling ran on the **same host** as the stack.
- **Toolchain:** Java 21.0.12, Maven 3.9.16, Docker 29.8.1, Spring Boot 4.1.1, Gatling 3.15.1
  (gatling-maven-plugin 4.21.12). New module `performance-tests/` (outside the reactor). No new
  infrastructure.
- **Result:** ✅ green.
  - `./mvnw clean verify`: BUILD SUCCESS, **616 tests** (480 unit, 136 integration), 0 failed,
    0 skipped.
  - Done-when: a bottleneck was **found** (the outbox relay, ~41 orders/s ceiling), **fixed**
    (`8204022`) and **documented** (`docs/performance.md`). At 50 orders/s, order settled p95 went
    from 25.9 s to 3.0 s.
  - Gatling acceptance runs on the cold final stack, default profile (steady, 20 sessions/s, 60 s):
    browse, checkout and mixed all passed their assertions with **0 failed requests**.
  - Compose, cold (`down -v`, `--build`, `.smoke-state` removed), the user's `.env`: smoke
    **404 / 0 / 0**.
  - Kubernetes (existing kind cluster, upgraded in place): smoke **360 / 0 / 7**, the same as
    Phase 29.

## 1. Full regression

`./mvnw clean verify`: BUILD SUCCESS, 616 tests, up from 610. New:

| Test | Kind | Count | What it proves |
|---|---|---|---|
| `OutboxRelayTest` | unit | 5 | an idle outbox is one query; a backlog drains in one tick while batches are full; a short batch (failed send) ends the tick; never more than `maxBatchesPerTick`; a structural failure ends the tick |
| `OutboxPropertiesTest` | unit | +1 | `maxBatchesPerTick` defaults to 20 and refuses zero or less (the other cases now also assert it) |

The simulations are not in `verify`, because they need a running stack. CI compiles them
(`./mvnw -B -f performance-tests/pom.xml test-compile`, a new step), and so did every run below.

## 2. Phase acceptance: the Done-when item

> At least one bottleneck is found, fixed, and documented.

| | Evidence |
|---|---|
| Found | Checkout ramp 1 -> 100/s: 594 of 6 060 orders not CONFIRMED within 30 s, settle p95 46.3 s. App `outbox_event`: 12 484 events, `published_at - created_at` avg 23.5 s / max 55.1 s. Inventory's and payment's outboxes stayed under 1.5 s. Batches of exactly 100 took ~217 ms each and were ~1.2 s apart, a ceiling of ~82 events/s (2 per order) |
| Fixed | `OutboxRelay` drains while batches are full, up to `ecomdemo.outbox.max-batches-per-tick` (20) |
| Before / after | `PERF_RATE=50 PERF_DURATION_SECONDS=60 scripts/perf-test.sh checkout steady`: settle p50/p95/p99 **13.0/25.9/26.7 s -> 2.5/3.0/3.3 s**; outbox wait avg **12.5 s -> 0.98 s**; status-poll p99 **21.8 s -> 9 ms**. Ramp to 100/s repeated: unconfirmed **594 -> 0**, settle p95 **46.3 s -> 5.4 s** |
| Documented | `docs/performance.md`, `docs/decisions.md` (Phase 30), the relay's javadoc (which had claimed "about a hundred events a second") |

## 3. Phase checklist

| Item | How it was checked |
|---|---|
| Browse, checkout, mixed simulations | each run with each profile at least once (§4); acceptance runs at defaults, 0 KO |
| Ramp, steady, spike profiles | browse ramp to 200 and 600/s; checkout ramp to 100/s; steady for every comparison; mixed spike (3 000 sessions in 10 s) |
| Cache on/off, pool sizes | `scripts/perf-compare.sh cache` (steady 200/s and ramp to 600/s) and `app-pool` (2, 5, 10, 30 at 80/s) |
| Findings | `docs/performance.md`; every labelled run's raw lines in `docs/test-reports/phase-30-perf-results.tsv` |

## 4. Runs (all through the gateway, compose stack)

| Label | Simulation / profile | Headline |
|---|---|---|
| explore-browse-ramp | browse ramp -> 200/s | 392 req/s, p99 6 ms, 0 KO |
| explore-checkout-ramp | checkout ramp -> 100/s | knee ~36 orders/s; 594 unconfirmed |
| before-outbox-fix | checkout steady 50/s | settle p95 25.9 s; poll p99 21.8 s |
| after-outbox-fix | checkout steady 50/s | settle p95 3.0 s; poll p99 9 ms; 0 KO |
| after-fix-checkout-ramp | checkout ramp -> 100/s | 0 unconfirmed; settle p95 5.4 s |
| cache=redis / cache=none | browse steady 200/s | p99 9 ms / 38 ms, max 118 / 1 038 ms, 0 KO both |
| cache=redis / cache=none | browse ramp -> 600/s | similar latency (gateway-bound); cache off: inventory-service 0.3 % -> 148 % CPU, both DBs ~0 -> 34-37 % |
| app-pool=2 / 5 / 10 / 30 | checkout steady 80/s | 2: 3 339 of 6 015 checkouts failed; 5/10/30: 0 KO, settle p95 4.7/4.7/4.4 s |
| mixed-spike | mixed spike (buggy split) | 300 checkouts/s: pool exhausted (`waiting=200`), 13.5 % KO, browse 0 KO |
| mixed-spike-fixed | mixed spike 3 000 in 10 s | 66 956 requests, 0 KO, p99 113 ms; 2 400 orders all CONFIRMED |
| acceptance-browse / -checkout / -mixed | steady 20/s (defaults), cold final stack | 0 KO; p95 6 / 13 / 9 ms; settle p95 3.0 s (checkout) |

**A bug found in the test itself, and fixed:** the mixed simulation scaled the arrival rate by each
scenario's share but gave both scenarios the whole spike. Its first spike run was therefore 3 000
checkouts in 10 s, not 600 (`3b8ede8`). That run is kept in `docs/performance.md` as the
overload example, labelled as such.

## 5. Compose

Cold: `docker compose down -v`, `.smoke-state` removed, `docker compose up -d --build`, every
container with a health check healthy. A copy of `scripts/smoke-test.sh`: **404 passed, 0 failed,
0 skipped**, with the user's `.env` (Ollama for chat and embeddings, assistant on `qwen2.5:7b`).
That includes the Kafka-outage section, where the outbox holds events and then drains them. It's
the path the relay change touches.

No new smoke checks, as the phase file specifies ("Gatling results are added to the test report").

## 6. Kubernetes (kind)

`scripts/k8s-up.sh` on the existing cluster, then `kubectl rollout restart` of app,
catalog-service, inventory-service, payment-service and gateway-service (a re-used `:latest` tag
doesn't restart pods). All rolled out. `max-batches-per-tick` is confirmed present in the app pod's
`application.properties`. `scripts/k8s-smoke.sh`: **360 / 0 / 7**. The seven SKIPs are the same as
Phase 29: observability isn't in the cluster, and AI providers are `none`.

## 7. ⚠️ Needs you

1. **Numbers are relative.** Gatling shared the host with the stack, so the absolute ceilings are
   this workstation's, and some of the tail latency at the highest rates is the host itself. For
   capacity numbers, run the load generator on a different machine (`PERF_BASE_URL`).
2. **Try it:** `scripts/perf-test.sh checkout ramp` with `PERF_RATE=100`, then open the HTML report
   it prints. The *order settled* line in the console is the saga's time. Gatling's own group line
   is cumulated request time.
3. **Soak testing** (hours at normal load) was not run; it's listed under follow-ups.

## 8. Clean-up

No stray processes. Load-test output is under `performance-tests/target/` (gitignored). The compose
stack (the user's `.env`) and the kind cluster are running, as before the phase, with the defaults
restored after every comparison. The perf users (`perf-user-1..200`) and the 20 `Perf Product N`
items stay in the compose databases (created by the acceptance runs after the smoke test). Setup
reuses them, and `docker compose down -v` removes them.

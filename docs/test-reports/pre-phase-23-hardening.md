# Test Report: pre-Phase-23 hardening

- **Date:** 2026-09-27
- **Branch:** `fix/pre-phase-23-hardening`, cut from `main` at `4892cb6` (tagged `phase-22-complete`)
- **Machine:** the WSL2 workstation. Every result below was produced by Claude Code on it.
- **Why this branch exists:** to fix, before Phase 23 (tracing across HTTP and Kafka), the defects that
  would make its verification ambiguous. Everything else on the carried list stays with its phase.
- **Result:** ✅ `./mvnw clean verify` BUILD SUCCESS **with the full stack running**; smoke
  **313 / 0 / 0** on **three consecutive cold starts**.

## 1. What was fixed

| # | Defect | Evidence before | Evidence after |
|---|---|---|---|
| 1 | A cold start could leave 2 of 3 `orders.placed` partitions unconsumed for minutes | forced race: **1 of 9** events in 60 s; smoke poison checks failed **2 of 5** cold runs | forced race: **9 of 9 in 1–2 s** (×3); poison checks **6 of 6** cold runs |
| 2 | JVM heap share left too little room outside the heap | notification-service at **97–98 %** of 320 MiB; a full heap could not fit | every service **34–48 %** of its limit; no OOM kill, no restart |
| 3 | `EdgeSecurityIT` passed only when nothing listened on 8081–8084 | **4 failures** on `main` with the stack up | gateway 7 + 15 pass with 17 containers running |
| 4 | Phase 19 dashboard: two stats reduced an instantaneous rate | failed-checkout ratio for a quiet minute: **`NaN`** (skipped by `lastNotNull` → stale value shown) | **`0`**; all four stats are instant queries over `$__range` |
| 5 | Phase 22's "breaker CLOSED again" smoke check asserted a state at an instant | failed **1 of 3** cold runs on a correct breaker | polls for the claim; **3 of 3** |

### 1.1 The Kafka partition race — the mechanism, measured

The broker log of a cold start showed it directly:

    19:03:18.473  Created log for partition orders.placed-0          (with the retry and DLT topics)
    19:03:21.334  Created log for partition orders.placed-2
    19:03:21.343  Created log for partition orders.placed-1

`orders.placed` was created twice over by two services: notification-service's `@RetryableTopic`
(which creates the MAIN topic as well as its retry topics, with the broker default of one partition)
and ecomdemo-app's `NewTopic` with three. When notification-service started first, its consumer was
assigned partition 0 and did not see partitions 1 and 2 until a metadata refresh or a rebalance.

A reproduction forced that order on a fresh broker (`docker compose rm -sf kafka`, notification-service,
then the app) and published nine valid events:

    before:  after notification-service: PartitionCount: 1
             after app:                  PartitionCount: 3
             delivered within 60s: 1 / 9
               p0: offset 1 of 1 (lag 0)          <- no assignment for p1 or p2 at all

    after:   after notification-service: orders.placed absent
             after app:                  PartitionCount: 3
             delivered within 1s: 9 / 9           (runs 2 and 3: 2 s)
               p0/p1/p2 all assigned, lag 0

**Not memory.** The hypothesis that notification-service's memory pressure caused the stall was tested
and rejected: the stall reproduced deterministically from startup order alone.

**The IT had been hiding it.** `OrderPlacedConsumerIT` let `@RetryableTopic` create the topic, so it ran
against ONE partition, where "a poison message does not block the partition behind it" is trivially
true. It now declares the producer's three-partition layout before the first send, pins the poison and
the good event to the same partition, and a new test asserts every partition is consumed.
`TopicOwnershipTest` holds the one-owner rule (mutation: `autoCreateTopics = "true"` → fails).

### 1.2 Memory — a ratio, not a size

Measured after a full smoke run (Prometheus, per application):

    application           heap  peak(1h)  heap max  non-heap
    catalog-service         70        85       278       175
    customer-service        51        66       232       143
    ecomdemo               85       112       557       179
    gateway-service         45        63       232       107
    inventory-service       50        67       278       160
    notification-service    47        78       232       151

Non-heap is a fixed cost per JVM. At `MaxRAMPercentage=75` a 320 MiB container allows a 232 MiB heap,
and 232 + ~150 exceeds 320 — the kernel would kill the container before Java could throw. Now 50 % of
768 MiB (1 GiB for the app). `ContainerMemoryBudgetTest` requires limit × (1 − share) ≥ 256 MiB for all
six services, and that the Dockerfile and compose override agree (mutation: Dockerfile back to 75 % →
both tests fail).

After three cold smoke runs with the new limits:

    ecomdemo-app                  454 MiB / 1 GiB    44 %
    ecomdemo-catalog-service      366 MiB / 768 MiB  48 %
    ecomdemo-customer-service     316 MiB / 768 MiB  41 %
    ecomdemo-gateway-service      262 MiB / 768 MiB  34 %
    ecomdemo-inventory-service    323 MiB / 768 MiB  42 %
    ecomdemo-notification-service 334 MiB / 768 MiB  44 %
    all 17 containers             2973 MiB

## 2. Full regression

    ./mvnw clean verify   with ALL 17 containers running
    common 12 · inventory 34 · catalog 41 + 12 · customer 43 + 8 · notification 10 (+2) + 4 (+1)
    gateway 7 + 15 · ecomdemo-app 234 (+3) + 53
    BUILD SUCCESS in 2:09

The first `verify` ever run with the stack up. On `main` it failed (finding 3); on this branch it passes.

## 3. Smoke, all runs on this branch

| Run | Stack | Result |
|---|---|---|
| 1–3 | cold, fresh images | 313/0, 313/0, **312/1** — the breaker check (finding 5) |
| 4–6 | cold, same images, fixed check | **313/0/0**, **313/0/0**, **313/0/0** |

The limits were set in the shell for these runs (`APP_MEMORY_LIMIT=1G GATEWAY_MEMORY_LIMIT=768M`)
because the user's `.env` pins the old values — see §5.

## 4. Merge verification of Phase 22 (done first, on `main`)

PR #35 merged with a merge commit; ancestor check passed; 0 missing commits; 0 file differences; branch
alive; CI green on `4892cb6`; `verify` BUILD SUCCESS in 2:06 (stack down); cold smoke **313/0/0**.
Tagged `phase-22-complete`. On the way: `verify` with the stack up failed (finding 3), and one cold smoke
run failed the poison checks (finding 1) — both are this branch's reason to exist.

## 5. ⚠️ Manual steps for you

1. **Your `.env` pins the old limits**: `APP_MEMORY_LIMIT` and `GATEWAY_MEMORY_LIMIT`. Change them to
   `1G` and `768M`, or delete both lines to take the new defaults.
2. **A local, uncommitted edit of yours is in `git stash`** (`stash@{0}`, "prometheus port 19090"). It
   changed BOTH sides of the port mapping (`19090:19090`), and Prometheus listens on 9090 inside its
   container, so nothing answered on the host port. It was stashed, not discarded, so `main` could be
   verified as committed. Windows no longer reserves 9090, so it is not needed now; if the reservation
   returns, set `PROMETHEUS_PORT=19090` in `.env` and export it for the smoke test — compose and the
   smoke test both follow it. Then `git stash drop` it, or `git stash pop` to have it back.
3. **Look at the overview dashboard's top row** (⚠️ appearance is not machine-checked): all four stats
   now describe the time range in the picker.

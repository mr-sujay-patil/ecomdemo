## Phase 30: Performance Testing (tag: phase-30-complete, PR #47)
**What exists now:** Gatling load tests in `performance-tests/` (own pom, NO Boot parent, NOT in the
reactor; CI only test-compiles it): Browse, Checkout (cart -> order -> poll status to CONFIRMED),
Mixed (80/20) x ramp/steady/spike, all through the gateway. The outbox relay now drains while
batches are full (checkout ceiling was ~41 orders/s; now no knee up to 100/s).
**Key code:** `com.ecomdemo.perf` (test sources): `PerfConfig` (PERF_* env), `LoadProfile`
(`injection(share)` scales rate AND spike), `Scenarios`, `TestData` (setup via java.net.http:
perf-user-1..200, `Perf Product 1..20` stock 1 000 000, 429 retry), `SettleTimes` (201 ->
CONFIRMED percentiles, printed in `after()`). `outbox/internal/OutboxRelay` loop +
`OutboxProperties.maxBatchesPerTick` (5th record component).
**Config & infrastructure:** `ecomdemo.outbox.max-batches-per-tick=20` (app properties; default 20
everywhere). compose knobs, defaults = today: `CATALOG_CACHE_TYPE` (redis|none),
`CATALOG_DB_POOL_SIZE`, `APP_DB_POOL_SIZE` (env `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`, no
underscore inside MAXIMUMPOOLSIZE). Run: `scripts/perf-test.sh <browse|checkout|mixed>
[ramp|steady|spike]`, `scripts/perf-compare.sh <cache|app-pool>`; results
`performance-tests/target/perf-results.tsv`, reports `performance-tests/target/gatling/`.
**Tests:** 616 (+6): `OutboxRelayTest` 5, `OutboxPropertiesTest` +1. No smoke additions (by spec).
Compose cold 404/0/0, k8s 360/0/7. Findings: `docs/performance.md`; raw runs
`docs/test-reports/phase-30-perf-results.tsv`.
**Gotchas:** Gatling 3.15 writes no stats.json (the script parses index.html's table); the plugin forks
the JVM, so `-D` doesn't reach simulations (use env). Gatling group stats are CUMULATED request time,
not duration. Anonymous requests from one host share one rate-limit bucket (50/s). JWTs last 15 min,
so runs must stay shorter. `placeOnce()` holds a DB connection across the inventory HTTP call: pool 2
collapses. At high read rates the GATEWAY is the first CPU limit. Gatling shares the host: numbers
are relative.
**Follow-ups (not done):** stock check outside the checkout transaction; checkout load shedding
(503 + Retry-After before the pool queues); pipelined outbox sends; shorter poll-delay (~1.5 s settle
floor = 3 hops); push instead of poll for order status; gateway cost per request (rate-limit Redis
call + JWT) and replicas; separate load machine; soak test.

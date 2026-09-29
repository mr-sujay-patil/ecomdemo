# Recent Phase Summaries (rolling window: last 2 phases)

> Newest first. When a third summary is added, move the oldest to `docs/progress/archive/phase-XX-summary.md`. Maximum ~30 lines per summary: facts only, no narrative.

<!-- TEMPLATE
## Phase XX: <Title> (tag: phase-XX-complete, PR #N)
**What exists now:** <1–3 lines describing the system after this phase>
**Key code:** <packages and classes that matter next>
**Config & infrastructure:** <profiles, env vars, ports, containers, and how to run>
**Tests:** <new test classes, smoke test additions>
**Gotchas:** <anything surprising the next phase must know>
**Follow-ups (not done, out of scope):** <suggestions deferred to later phases>
-->

## Phase 31: Security Scanning (tag: phase-31-complete, PR #48)
**What exists now:** CI jobs `dependency-scan` (OWASP Dependency-Check 13.0.0, NVD, CVSS >= 7 fails,
test scope skipped) and `image-scan` (all 8 images built in one job, Trivy 0.74.0 by digest,
HIGH/CRITICAL fail, CycloneDX SBOM artifact per image); `publish` needs both. `docs/security.md` =
scan findings + OWASP API Top 10 (2023) review. catalog/inventory now enforce roles themselves.
**Key code:** root pom: `dependency-check-maven` in pluginManagement (run explicitly:
`NVD_API_KEY=... ./mvnw org.owasp:dependency-check-maven:aggregate`), security version overrides
`tomcat.version` 11.0.25, `jackson-bom.version` 3.1.6, `jackson-2-bom.version` 2.21.6 (REMOVE when Boot
manages >= these). `dependency-check-suppressions.xml` (2 false positives, until 2027-03-31),
`.trivyignore.yaml` (empty). catalog `SecurityConfig`: GET any token, writes + `/embeddings/**`
ADMIN|SERVICE; inventory: everything ADMIN|SERVICE (`ServiceTokens.ROLE`).
**Config & infrastructure:** GitHub secret `NVD_API_KEY` (set from the user's `.env`); NVD data cached
in CI (`~/.cache/dependency-check`, first download ~26 min in CI, ~40 min locally); locally the key
is read from `.env` (never print it). `trivy-reports/` gitignored.
**Tests:** +4: `InventorySecurityTest` (CUSTOMER 403 on read/write/reserve, ADMIN ok), `ProductApiIT`
(CUSTOMER reads but 403 on create/delete/backfill; ADMIN writes). CI blocking proven on PR #48 with
a temporary commons-text 1.9 commit, then reverted.
**Gotchas:** Dependency-Check 13 will not run without an NVD key. CPE matching is product-wide:
Kotlin build-tool CVEs hit kotlin-stdlib; pgvector extension CVEs hit the Java client. Dependency-Check
groups related jars (kotlin-reflect under kotlin-stdlib). Trivy writes root-owned files through the
docker socket mount (delete via a container).
**Follow-ups (not done):** login throttling per username; asymmetric JWT + scoped service identities;
bind compose ports to 127.0.0.1; gateway `/actuator/prometheus` not public; pagination on
`GET /api/products`; Trivy config/IaC scanning of Dockerfile and k8s manifests; Dependabot/Renovate.

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

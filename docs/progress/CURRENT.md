# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-26
- **Phase:** 21: API Gateway (Spring Cloud Gateway)
- **Branch:** feature/phase-21-gateway
- **Step:** PLANNING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES — approve `docs/phases/phase-21-plan.md`

## Phase 20 merge verification (PASSED 2026-09-26) — `phase-20-complete` IS TAGGED
Four PRs: #29 `e34330d`, #30 `380ad6f`, #31 `a588d4b`, #32 `f819d3e`. All MERGED, no open PRs, the
feature branch alive on the remote. `main` at **f819d3e**; the tag points there.
- Structural: branch is an ancestor of `main`, **0** commits missing, **0** file differences. Both of
  #32's fixes verified present in the working tree, not merely in a merge commit.
- `./mvnw clean verify` on `main` → 10 + 34 + 41 + 12 + 43 + 8 + 8 + 3 + 228 + 53, BUILD SUCCESS in
  **1:43**, failsafe bound in all four IT modules.
- `scripts/smoke-test.sh` on `main`, **COLD** stack → **275 passed, 0 failed, 0 skipped**.
- `OrderApiIT` ran in **11.95s**. The same class took **780s and timed out** during 20d verification
  with 17 containers competing for the Docker daemon. That confirms the earlier diagnosis — contention,
  not a code defect. Always verify with the compose stack DOWN.

## What exists now (after Phase 20)
- **Five deployables**, sixteen containers, five databases, **1459 MiB of 3916**.
- `ecomdemo-app` **8080** (cart, orders, outbox, batch + two proxies) · `ecomdemo` db 5432
- `catalog-service` **8081** (product + the Redis cache) · `catalog_db` 5434, Flyway V2
- `inventory-service` **8082** (product_stock) · `inventory_db` 5433, Flyway V2
- `customer-service` **8083** (users; the ONLY issuer of user tokens) · `customer_db` 5435, Flyway V1
- `notification-service` **8085** (notification + processed_event; no business API) · 5436, Flyway V1
- `common` holds `shared`, `jwt` (validation for everyone), `clients` (+ the identity a caller signs
  with). Package placement is load-bearing — see `docs/test-reports/phase-20d.md` §8.
- Kafka, Redis, Prometheus, Grafana, Loki, Alloy. `kafka-ui` behind `--profile tools`.

## Phase 21 scope (`docs/phases/phase-21-gateway.md`)
A `gateway-service` with routes to all five services; JWT validation at the edge; a Redis-backed rate
limiter; CORS; a correlation ID filter; a Spring Cloud release train matching Boot 4.1.1.
**Done when:** clients use only the gateway, and a request burst returns 429.

### The two things this phase actually fixes
1. **Two hand-written proxies disappear.** `ecomdemo-app` currently forwards `/api/products` to
   catalog-service and `/api/auth` + `/api/customers` to customer-service, purely to keep the split
   invisible to clients. That is the gateway's job.
2. **A proxied login puts a plaintext password through `ecomdemo-app`'s memory.** Routing login at the
   edge removes the app from that path entirely. This is the one security-relevant win of the phase.

## Next action
`docs/phases/phase-21-plan.md` is written and committed (1a2226e). **Waiting for the user's approval**,
per the 20c/20d rhythm. On approval, implement in this order: module + compose + the port move, then
routes, then JWT at the edge with `ApiError` parity, then the rate limiter, then CORS and the reactive
correlation filter, then delete the two proxies, then extend the three guard tests.

### Settled by measurement, not assumption (2026-09-26)
- **No GA Spring Cloud train exists for Boot 4.1.x.** 2025.1.3 is GA but baselined on Boot **4.0.8**;
  2026.0.0-M1 (Boot 4.2.0-M2) is a milestone, and the conventions require GA.
- **Chosen: 2025.1.3 + Boot 4.1.1**, probed in a throwaway project: one Framework version (7.0.9), one
  Boot version (4.1.1), **zero** 4.0.8 anywhere, and it started and really proxied (`Server:
  cloudflare` from the upstream; the gateway's own 404 on an unrouted path).
- **The gateway takes 8080; `ecomdemo-app` moves to 8084.** The smoke test uses exactly one `BASE_URL`
  and makes **zero** direct calls to 8081–8085, so ~200 existing checks start flowing through the
  gateway unchanged. The ~25 white-box `/actuator/**` checks need a new `APP_URL` helper — to be
  edited deliberately, NOT by bulk regex.
- **A correlation ID already exists** (`common/.../logging/CorrelationId`, `X-Correlation-Id`, MDC
  `correlation_id`) as a **servlet** filter, which does nothing in a reactive gateway. The phase needs
  a WebFlux filter reusing the SAME constants, not a second spelling.

## ⚠️ Carried, not fixed (oldest first)
- **Phase 19 dashboard defect** — two `EcomDemo Overview` stat panels reduce an instantaneous rate with
  `lastNotNull`. Branch `fix/dashboard-stat-reducers`. **Now the oldest open item.**
- A failed compensating release leaks a reservation; nothing reconciles it.
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify. Wants asymmetric keys + JWKS.
- `ecomdemo-app` is not yet named `order-service` (cosmetic; touches every image tag and compose ref).

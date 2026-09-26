# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-26
- **Phase:** 21: API Gateway (Spring Cloud Gateway)
- **Branch:** feature/phase-21-gateway
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES — review the PR

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
**Phase 21 is complete and verified. PR raised; waiting for review.**
On `approved, merge it`: `gh pr merge <n> --merge`, then merge verification per
`docs/process/execution-protocol.md` §5 with the compose stack **down** before `./mvnw clean verify`,
then `git tag phase-21-complete && git push origin phase-21-complete`.

### Verified
- `./mvnw clean verify`, full reactor: **10 + 34 + 41+12 + 43+8 + 8+3 + 7+15 + 218+53**, BUILD SUCCESS
  in 3:25.
- `scripts/smoke-test.sh` → **295 passed, 0 failed, 0 skipped** against 17 live containers.
- Memory **1387 MiB of 3916 with SIX services** — less than the 1459 five services cost.
- Full write-up: `docs/test-reports/phase-21.md`.

### The five defects only running found (detail in the report)
1. **Optional deps broke four services** — `OpenApiConfig` is a scanned `@Configuration` whose bean
   method returns springdoc's type; a missing annotation is ignored, a missing signature type is not.
2. **Exclusions are per DECLARATION** — the `test-jar` declaration inherited none, so Tomcat arrived at
   test scope, Boot built a SERVLET context, and `@EnableWebFluxSecurity` clashed. Passed standalone
   (stale `common` in `~/.m2`), failed in the reactor.
3. **`@AutoConfigureWebTestClient` is for MOCK slices** — bound `WebTestClient` to the port by hand.
4. **Deleting the proxies removed a SERVICE TOKEN** — `GET /api/products` returned 401 from
   catalog-service for a path the gateway had permitted. No IT could catch it (they run no upstream).
   `ServiceIdentityFilter`, ordered AFTER security, restores it.
5. **The rate-limit check was testing its own client** — sequential curl runs below the refill rate, and
   `xargs -P 20` landed on exactly 50/s. One curl, one connection, 300 requests, 40 in flight: 171
   served / 129 refused in 2.1s.

### ⚠️ Environment notes for the next session
- **Gateway 8080, app 8084.** `.env` was updated locally (it is gitignored); `.env.example` is committed.
- **Never build a Docker image while the stack is up on this machine** — six JVMs plus BuildKit running a
  reactor build exhausted the host; three commands were killed and BuildKit's frontend crashed.
- **catalog-service peaked at 98% of its 384M cap** after the burst check, settling to 88%. No OOM.

## ⚠️ Carried, not fixed (oldest first)
- **Phase 19 dashboard defect** — two `EcomDemo Overview` stat panels reduce an instantaneous rate with
  `lastNotNull`. Branch `fix/dashboard-stat-reducers`. **Now the oldest open item.**
- A failed compensating release leaks a reservation; nothing reconciles it.
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify. Wants asymmetric keys + JWKS.
- `ecomdemo-app` is not yet named `order-service` (cosmetic; touches every image tag and compose ref).
- **NEW: the catalogue is documented nowhere** — catalog-service declares no springdoc, and the app's
  spec only had `/api/products` because it proxied it. A gateway can aggregate specifications.
- **NEW: a correlation ID cannot be followed across the hop** — only `ecomdemo-app` configures
  structured logging, so the other five never write the ID to the log line.
- **NEW: the gateway does no request logging**, so no credential redaction either — and login now
  passes through it. Nothing leaks today (Gateway logs no bodies by default); it is an absence, not a
  decision.

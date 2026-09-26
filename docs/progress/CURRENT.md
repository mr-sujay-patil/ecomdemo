# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-26
- **Phase:** 21: API Gateway (Spring Cloud Gateway)
- **Branch:** feature/phase-21-gateway
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO — plan approved, implementing

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
**Everything is implemented and committed; `./mvnw clean verify` PASSES on the branch. What remains is
the cold smoke run, which was interrupted by the HOST RUNNING OUT OF MEMORY, not by a defect.**

Resume with, in order:
1. `docker compose --profile tools down --remove-orphans` — free the 17 containers first.
2. `docker compose build gateway-service` — the image still has the pre-`ServiceIdentityFilter` jar.
   Build it ALONE; `docker compose up --build -d gateway-service` also rebuilds `app` and BuildKit
   died with "frontend grpc server closed unexpectedly" while both ran.
3. `docker compose up --wait` then `./scripts/smoke-test.sh`.
4. Measure memory with six services (`docker stats --no-stream`), write
   `docs/test-reports/phase-21.md`, raise the PR.

⚠️ **Do not run a Docker build while the full stack is up on this machine.** Six JVMs plus a reactor
build inside BuildKit exceeded the host's memory: three background commands were killed and one
BuildKit frontend crashed. Bring the stack down, build, then bring it up.

### Verified so far
- `./mvnw clean verify`, full reactor: **10 + 34 + 41+12 + 43+8 + 8+3 + 7+15 + 218+53**, BUILD SUCCESS.
- 17 containers healthy, gateway on **8080**, app moved to **8084** (container port still 8080).
- Cold smoke run reached its FIRST check and failed there — `GET /api/products -> 401`. That defect is
  FIXED (`2f532f8`) but the fix is not yet in the running image. See step 2.

### Findings this phase (for the test report)
1. **No GA Spring Cloud train for Boot 4.1.x.** Chose 2025.1.3 (GA, Boot 4.0.8 baseline) after probing:
   one Framework version, one Boot version, zero 4.0.8, and it really proxied.
2. **Optional dependencies broke four services.** `OpenApiConfig` is a component-scanned
   `@Configuration` whose bean method returns springdoc's type; an absent annotation is ignored, an
   absent method-signature type is not. Reverted to per-declaration exclusions in gateway-service.
3. **Exclusions are per DECLARATION.** The `test-jar` declaration of the same artifact inherited none,
   so Tomcat arrived at test scope, Boot built a SERVLET context, and `@EnableWebFluxSecurity` clashed
   over `conversionServicePostProcessor`. It passed standalone (stale `common` in `~/.m2`) and failed
   in the reactor — only `./mvnw clean verify` told the truth.
4. **`@AutoConfigureWebTestClient` is for MOCK slices** — on RANDOM_PORT it force-imports the servlet
   security auto-configuration. `WebTestClient` is now bound to the port by hand.
5. **Deleting the proxies removed the SERVICE TOKEN nobody was thinking about.** catalog-service and
   inventory-service require an authenticated caller; the proxy signed for them. No IT could catch it,
   because by design no upstream runs in them — only a cold stack could.
6. **`.env.example` pinned `APP_PORT=8080`**, overriding compose's default: the gateway died with
   "port is already allocated". `.env` (gitignored) needed the same one-line change — done locally.
7. **A correlation id still cannot be followed across the hop.** Only `ecomdemo-app` configures
   structured logging, so the other five write the id to the MDC and never to the line. Documented in
   the smoke script where the check would have gone. Follow-up, not this phase.

## ⚠️ Carried, not fixed (oldest first)
- **Phase 19 dashboard defect** — two `EcomDemo Overview` stat panels reduce an instantaneous rate with
  `lastNotNull`. Branch `fix/dashboard-stat-reducers`. **Now the oldest open item.**
- A failed compensating release leaks a reservation; nothing reconciles it.
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify. Wants asymmetric keys + JWKS.
- `ecomdemo-app` is not yet named `order-service` (cosmetic; touches every image tag and compose ref).

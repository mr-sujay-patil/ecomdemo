# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-25
- **Phase:** 20d: Microservices Split — extract `customer-service` and `notification-service` (the
  LAST PR of Phase 20)
- **Branch:** feature/phase-20d-customer-service
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 20c merge verification (PASSED 2026-09-25 — NO TAG, by design)
PR #28 merged as merge commit **34f17f0** (parents d120607 + c222420). Every checklist item passed:
branch is an ancestor of `main`, **0** commits missing, **0** file differences, the feature branch
alive locally and on GitHub, all **25** remote branches intact. CI on `main`: **success**.
- `./mvnw clean verify` on `main` → **5 + 34 + 41 + 282 + 65**, BUILD SUCCESS, tree CLEAN afterwards.
- `scripts/smoke-test.sh` on `main` → **271 passed, 0 failed** against 12 healthy containers.
  ⚠️ The FIRST run failed one check — `"the checkout did not wait for the broker (under 10s)"` — with
  the app container 17 seconds old. It passed on the warm re-run and on every 20c run. Diagnosis: the
  first checkout after startup pays lazy initialisation that has grown with each extracted service,
  so the check now measures warm-up as much as the claim it makes. **Recorded as a known flake with a
  fix planned in 20d** (§5 of the plan), not shrugged off.

**`phase-20-complete` is deliberately NOT tagged.** `git tag --list "phase-*"` ending at
`phase-19-complete` is correct. THIS phase tags it, once its PR merges and verifies.

## ⚠️ Phase 20 ships a PR PER SERVICE — user's decision, 2026-09-24
20a (#26) prepared · 20b (#27) inventory-service · 20c (#28) catalog-service · **20d is the last**.
Each extraction has produced failures `./mvnw clean verify` could not see — three in 20c alone — and
the tests written for them now fail automatically for the next service. That is the rhythm's payoff.

## What exists now (after 20c)
- **Three deployables**, twelve containers, **1486 MiB of 3916**. A service idles at 170–183 MiB.
- `catalog-service` owns `product` + the Redis cache (`catalog_db`, 8081/5434).
- `inventory-service` owns `product_stock` (`inventory_db`, 8082/5433).
- `ecomdemo-app` is the rest, and keeps the PUBLIC `/api/products` as a temporary proxy — Phase 21's
  gateway replaces it.
- `common` holds `shared`, `jwt`, `clients` (the HTTP clients + the identity a caller signs with),
  `logging`, `metrics`, and a **test-jar** (ProjectRoot, container configs).
- **"Everyone verifies, only callers sign"** is enforced by package placement: `com.ecomdemo.jwt` is
  scanned by every service, `com.ecomdemo.clients` only by services that call one.

## Checklist for 20d — plan APPROVED 2026-09-25, implementing
- [x] Plan approved (`docs/phases/phase-20d-plan.md`), with both open decisions settled:
      **§2.1 identity comes from the TOKEN** — `CurrentUser` reads `uid`/`roles` from the claims
      rather than loading a `User`, because otherwise every authenticated request becomes an HTTP
      call to learn what the request already carried. Accepted cost: a cart holds a `userId` with no
      foreign key, so a deleted account leaves orphaned carts.
      **Tests MINT a token** rather than logging in, following `CatalogIntegrationTest` from 20c — a
      service with no login endpoint should not have tests that log in.
- [x] **`CurrentUser` reads the token instead of loading a User** — DONE (212ed66). Cart and order
      hold a `userId`; V15 drops both foreign keys AND snapshots/backfills `orders.username`.
      Verified against **686 real orders**: `./mvnw clean verify` 5 + 34 + 41 + 283 + 65, smoke
      **273 passed / 0 failed**.
      **The backfill was the part with a deadline and the plan had not spotted it.**
      `Order.getUsername()` read through the association and that name shows in the API response and
      the audit row. New orders can take it from a claim; existing ones have it nowhere, and after
      the split NO query can fill it in. V15 is the last migration that can see both tables.
- [ ] `customer-service`: `users` in `customer_db` at V1, register/profile, login, the JWT **encoder**
- [ ] `notification-service`: `notification` + `processed_event` at V1, the consumer, the deduplicator
- [ ] The app keeps a token-validating chain but loses `AppUserDetailsService`/`AuthenticationManager`
- [ ] Compose, Alloy, Prometheus, the Dockerfile's COPY list (a test guards that one)
- [ ] Fix the cold-start flake in the broker-timing smoke check (§5 of the plan)
- [ ] Smoke test across five services; full protocol; `docs/test-reports/phase-20d.md`; docs; PR
- [ ] **ONLY after that PR merges and verifies: tag `phase-20-complete`**

## Next action
**Step 1 is done, verified and pushed.** Continue with the plan's §3 step 2: the `customer-service`
module — `users` in `customer_db` at V1, the register/profile API, login, the `AuthenticationManager`
and the JWT **encoder** (the one thing that must NOT be in `common`, because only the service that
owns accounts may issue a user token).

Then step 3 (`CustomerGateway`), step 4 (`notification-service`), step 5 (the app keeps a
token-validating chain and forwards `/api/auth/**` and `/api/customers/**`), step 6 (compose, Alloy,
Prometheus, the Dockerfile's COPY list), step 7 (smoke across five services, report, docs, PR).

Expect the app's integration tests to break at step 2: every one of them calls
`IntegrationTest.asAdmin()`, which POSTs to `/api/auth/login`. The approved answer is to MINT a token
from the shared secret, following `CatalogIntegrationTest` from 20c — a service with no login
endpoint should not have tests that log in. The seeded `admin` row moves to `customer_db`, and the
smoke test logs in as it on its very first check.

## Known traps (do not rediscover)
- **A test classpath richer than the runtime classpath.** `spring-boot-restclient` at TEST scope let
  every test pass and the container fail: the auto-configured `RestClient.Builder` is declared there.
- **The Dockerfile has a per-module COPY list** and it goes stale silently until
  `docker compose build`. `DockerfileCoversEveryModuleTest` reads both lists.
- **A fake must mirror the COLLABORATION, not the data.** `InMemoryCatalog` stored a stock number
  instead of asking inventory on every read; four ITs caught it.
- **A GLOBAL `spring.json.value.default.type`** makes every Kafka topic inherit one payload type, and
  the failure shows **zero consumer lag**. Each listener names its own container factory.
- **Docker Desktop hangs on a modal error dialog**; `pgrep -f "name=error-dialog"` reveals it, and it
  needs a human to dismiss it. Blocked 20b's verification for ~40 minutes.
- **Never `docker compose up --build` while the stack runs** — two Maven builds in a 3.8 GB VM starve
  it. `down` first, or `build` then `up`.
- **`docker compose down` leaves `kafka-ui`** (it is behind the `tools` profile) and the network
  survives. Use `docker compose --profile tools down`.
- **`./mvnw compile` alone fails**: it needs `common`'s test-jar, produced at `package`.
- **`mvn test-compile` can report success against STALE test classes.** Use `clean`.
- **macOS `sed` has no `\b`.** Word-boundary replacements silently do nothing; use Python.
- **A bulk rename hits string literals.** It rewrote expected error messages in tests.

## Open issues carried forward
- **Phase 19 dashboard defect, unfixed.** Two `EcomDemo Overview` stat panels reduce an INSTANTANEOUS
  rate with `lastNotNull`, so `Orders placed / min` reads 0.00 for an hour containing 34 orders.
  Agreed: its own `fix/dashboard-stat-reducers` branch. Full entry in `docs/decisions.md`.
- **A failed compensating release leaks a reservation.** Nothing reconciles it.
- **The CSV import is a distributed write** with no shared transaction; restartable and idempotent, so
  the repair is to re-run it.
- **HS256 shared secret**: every service can mint as well as verify. Deliberate, recorded.

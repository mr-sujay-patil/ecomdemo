# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-25
- **Phase:** 20d: Microservices Split — extract `customer-service` and `notification-service` (the
  LAST PR of Phase 20)
- **Branch:** feature/phase-20d-customer-service
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #29 MERGED (e34330d) · follow-up **#30 OPEN** — https://github.com/mr-sujay-patil/ecomdemo/pull/30
- **Waiting for user:** YES - review the PR

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

## Checklist for 20d - ALL DONE
- [x] Plan approved; identity from the TOKEN; tests MINT tokens
- [x] **V15**: cart/orders hold a `userId`, both FKs dropped, and `orders.username` snapshotted +
      backfilled across **686 real rows** - the migration with a deadline, which the plan missed
- [x] **customer-service**: `users` in customer_db V1, seeded admin at **id 1**, login, the JWT encoder
- [x] **notification-service**: `notification` + `processed_event` in notification_db V1, the consumer,
      `EventDeduplicator` moved with the table it guards; its own OrderPlacedEvent and topic names
- [x] **V16**: drops users/notification/processed_event; what remains is order-service's own, by name
- [x] The app is a resource server + two proxies (`identity`, `catalog`). No PasswordEncoder anywhere
- [x] Compose: 16 containers, Alloy + Prometheus cover all five, Dockerfile copies five module poms
- [x] **❗ Failsafe bound in every service - four modules' ITs had never run.** See the finding below
- [x] Cold-start flake fixed in the broker-timing check (warm up, and DRAIN before the outage)
- [x] `./mvnw clean verify`: 10+34+41+12+43+8+8+3+228+53, BUILD SUCCESS, 4 modules running ITs
- [x] `scripts/smoke-test.sh`: **274 passed / 0 failed**, twice, from cold. **1459 MiB of 3916**
- [x] `docs/test-reports/phase-20d.md`, README, decisions, RECENT (20b archived), tracker
- [x] PR #29 merged as **e34330d**; structural verification PASSED (ancestor, 0 missing, 0 diffs,
      branch alive, 26 remote branches). CI on `main`: success. `./mvnw clean verify` on `main`:
      **BUILD SUCCESS**, tree clean.
- [ ] ⚠️ **Smoke on `main` found ONE failure** - the race check demanded stock 0 instantly where the
      figure is eventually consistent. NOT an oversell: the 201/409 check and "exactly one order holds
      it" both passed. Fixed on the feature branch, raised as **PR #30**.
- [ ] PR #30 merged and verified - **and ONLY then: tag `phase-20-complete`**

## ❗ THE FINDING THAT MATTERS MOST (read `docs/test-reports/phase-20d.md` §0)
The parent declares Failsafe in `<pluginManagement>`, so a module opts in by naming it. `ecomdemo-app`
did. The four extracted services did NOT - so their `*IT` tests had not run since Phase 20b, and
`verify` printed BUILD SUCCESS the entire time, because an unbound plugin reports nothing at all: no
"0 tests" line, no warning, no skip count.

**This is a correction to the 20b and 20c test reports**, which described those tests as part of a
passing suite. Binding them found five real defects within minutes. `EveryModuleWithIntegrationTests
RunsThemTest` now fails the build if it recurs.

**A green build only means the things that ran passed.**

## Next action
**WAIT FOR THE USER on PR #30.** Merge verification for #29 passed every check except one smoke
assertion, so the tag is NOT yet applied - correctly. `git tag --list "phase-*"` still ends at
`phase-19-complete`.

After `approved, merge it` on #30: `gh pr merge 30 --merge`, then merge verification per
`execution-protocol.md` §5 - and THEN `git tag phase-20-complete && git push origin phase-20-complete`.

⚠️ **Run `docker compose --profile tools down` BEFORE `./mvnw clean verify`.** The first attempt at
verification on `main` failed with seventeen containers running: Testcontainers timed out on Ryuk and
on topic creation, and `OrderApiIT` took 780 seconds. Nothing was wrong with the code. This is now the
most expensive instance of a trap already in the list below.


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

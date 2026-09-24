# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-24
- **Phase:** 20c: Microservices Split — extract `catalog-service` (the third PR of Phase 20)
- **Branch:** feature/phase-20c-catalog-service
- **Step:** PLANNING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** YES — the plan needs approval before any code moves

## Phase 20b merge verification (PASSED 2026-09-24 — NO TAG, by design)
PR #27 merged as merge commit **d120607** (parents 7e826ab + 122b431). Every checklist item in
`git-workflow.md` passed: branch is an ancestor of `main`, **0** commits missing, **0** file
differences, the feature branch still exists locally and on GitHub, and all **24** remote branches
are intact. CI on `main`: **success**.
- `./mvnw clean verify` on `main` -> **5 + 34 + 317 + 81**, 0 failures, 0 skipped, BUILD SUCCESS.
  Working tree stayed CLEAN afterwards, so the Phase 19 diagram determinism still holds.
- `scripts/smoke-test.sh` on `main` -> **270 passed, 0 failed, 0 skipped** against 10 healthy
  containers. Cache eviction converged on the first poll (`took 0ms`).

**`phase-20-complete` is deliberately NOT tagged**, and `git tag --list "phase-*"` ending at
`phase-19-complete` is CORRECT. Phase 20 is complete when the LAST service is extracted. The
pre-flight step "previous phase tag must exist" is knowingly skipped for the same reason.

## ⚠️ Phase 20 ships as a PR PER SERVICE — decided by the user 2026-09-24
20a (PR #26, merged) prepared the split. 20b (PR #27, merged) extracted `inventory-service`. This is
20c. The argument is `docs/test-reports/phase-20b.md` §8: extracting the FIRST service produced four
failures `./mvnw clean verify` could not see, and three of the four now fail the build automatically
for this one. The plan is still `docs/phases/phase-20-plan.md`.

## What exists now (after 20b)
- **Two deployables.** `inventory-service` owns stock in `inventory_db` (Flyway V1-V2), exposes
  `/api/inventory`, publishes `inventory.stock-changed`, validates JWTs with its own filter chain.
- `common` holds the shared kernel: `shared`, `jwt` (key + DECODER + roles converter + service
  tokens), `logging`, `metrics` — and publishes a **test-jar** (`ProjectRoot`).
- The app calls inventory-service as ITSELF (subject `ecomdemo-app`, role `SERVICE`), because the
  anonymous product listing and the CSV import's background thread have no token to relay.
- Compose: 10 containers (+ `kafka-ui` behind `--profile tools`). App 8080, inventory 8082,
  `ecomdemo` db 5432, `inventory_db` 5433.

## Checklist for 20c — NOT STARTED, plan not yet approved
- [ ] Plan posted and approved by the user
- [ ] `catalog-service` module: pom, application class, move `com.ecomdemo.catalog`
- [ ] It takes `cache` WITH IT — what it caches is the catalogue (a 20a finding, see decisions.md)
- [ ] `catalog_db` with its own Flyway history starting at **V1**; `product` DDL + seed carried over
- [ ] A `CatalogGateway`/`CatalogClient` seam in `ecomdemo-app`, mirroring the inventory one
- [ ] Its own `SecurityConfig`; the app calls it with a SERVICE token
- [ ] It consumes `inventory.stock-changed` (the cache evictor moves with `cache`)
- [ ] Compose: `catalog-db` + `catalog-service`, 384M limit, health check, Alloy + Prometheus
- [ ] Full testing protocol + `docs/test-reports/phase-20c.md`
- [ ] README, decisions.md, RECENT rotation, tracker; PR. **Still NO tag.**

## Next action
**Post the 20c plan and STOP for approval** (execution-protocol §3 step 4). Do not move code first.
The plan must answer, before anything else, the four questions that 20b proved are where the cost
is — each one is a thing that passed `clean verify` and still broke:
1. **Seed data.** `product` has a 10-row seed and `inventory_db` already hard-codes ids 1-10 against
   it. Moving `product` to `catalog_db` means `SeededStockAgreesWithTheCatalogueTest` must still
   hold across THREE databases. Decide how before moving anything.
2. **The `cache` module moves too**, and it holds the Kafka listener that evicts on a stock change.
   That listener needs its own container factory in its new home — the global
   `spring.json.value.default.type` trap that cost an afternoon in 20b.
3. **Who still reads the catalogue?** 20a removed `order -> catalog` entirely (checkout reads the
   cart's snapshot). `batch` does. Confirm from the module diagram before designing the client.
4. **A third JVM.** Measured budget: Docker Desktop caps at 3.8 GB, a JVM idles at ~296 MiB. Three
   services plus three databases plus Kafka and the observability stack needs re-measuring, not
   assuming. 20b's projection was ~2.0-2.4 GB with TWO services.

## Known traps (cost time already; do not rediscover)
- **Docker Desktop hangs on a modal error dialog** and the daemon never starts; `docker info` just
  says "Cannot connect". `pgrep -f "name=error-dialog"` reveals it. It needs a human to dismiss it,
  then a clean `open -a Docker`. This blocked the 20b merge verification for ~40 minutes.
- **Testcontainers reports "Could not find a valid Docker environment"**, which reads like a code
  failure and is not one. Only `ConcurrentReservationTest` needs it.
- **Never `docker compose up --build` while the stack is running.** Two Maven builds inside a 3.8 GB
  VM starve it. `down` first, or `build` then `up`.
- **`./mvnw compile` alone fails** on ecomdemo-app: it needs `common`'s test-jar, produced at the
  `package` phase. `test-compile`, `test` and `verify` are all fine.
- **`mvn test-compile` can report success against STALE test classes.** Use `clean`.
- **`docker compose down` leaves `kafka-ui` running** — it is behind the `tools` profile, so the
  network will not be removed. Use `docker compose --profile tools down`.
- **A freshly restarted app flakes the Prometheus smoke check once**: `rate(...[5m])` needs two
  samples. Re-run; not a regression.
- Checking out a branch while Grafana runs replaces the provisioning dir's inode
  (`docker compose up -d --force-recreate grafana`).

## Open issues carried forward
- **Phase 19 dashboard defect, still unfixed.** Two `EcomDemo Overview` stat panels reduce an
  INSTANTANEOUS rate with `lastNotNull`, so `Orders placed / min` reads 0.00 for an hour containing
  34 orders and `Failed checkouts` shows a STALE figure as though current. Agreed: its own
  `fix/dashboard-stat-reducers` branch. Full entry in `docs/decisions.md`.
- **A failed compensating release leaks a reservation.** `InventoryClient.release` logs and swallows,
  so stock can stay reserved for an order that never existed. Nothing reconciles it.
- **HS256 shared secret**: every service can mint as well as verify. Deliberate, recorded.

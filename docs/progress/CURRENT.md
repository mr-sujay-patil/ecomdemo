# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-25
- **Phase:** 20c: Microservices Split — extract `catalog-service` (the third PR of Phase 20)
- **Branch:** feature/phase-20c-catalog-service
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** raised, see below
- **Waiting for user:** YES - review the PR

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

## Checklist for 20c - ALL DONE
- [x] Plan posted and approved (`docs/phases/phase-20c-plan.md`)
- [x] The inventory client moved into `common`, under `com.ecomdemo.clients`
- [x] `catalog-service` module, `catalog_db` with its own Flyway V1-V2 (DDL read from the RUNNING
      database, not reconstructed from four migrations)
- [x] `cache` moved with it, including the Kafka evictor and its own container factory
- [x] `CatalogGateway`/`CatalogClient` in `common`; cart and batch changed wiring, not logic
- [x] Its own `SecurityConfig`; the app calls it with a SERVICE token
- [x] The app keeps the PUBLIC `/api/products` as a temporary proxy (Phase 21 replaces it)
- [x] `V14__drop_product.sql` - the app stops owning the catalogue
- [x] Compose: `catalog-db` + `catalog-service`, 384M, Alloy + Prometheus cover all three
- [x] Smoke test across three services: **270 cold / 271 warm**, 0 failed
- [x] `./mvnw clean verify`: **5 + 34 + 41 + 281 + 65**, BUILD SUCCESS
- [x] `docs/test-reports/phase-20c.md`, README, decisions.md, RECENT rotation (20a archived), tracker
- [ ] PR merged and verified - and ONLY then does 20d start. **Still NO tag.**

## Next action
**WAIT FOR THE USER.** The PR is open; the phase is at its stop point. Do not start 20d.

**`phase-20-complete` must NOT be tagged when this merges.** Two services remain - customer and
notification - and `order-service` is what `ecomdemo-app` becomes. Phase 20 is complete when the
LAST one is out. `git tag --list "phase-*"` ending at `phase-19-complete` is correct.

After `approved, merge it`: `gh pr merge <n> --merge`, never squash, never `--delete-branch`, then
merge verification per `docs/process/execution-protocol.md` §5, then cut
`feature/phase-20d-customer-service` from main.


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

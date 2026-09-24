# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-24
- **Phase:** 20b: Microservices Split - EXTRACTION (the second half of Phase 20)
- **Branch:** feature/phase-20b-microservices
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## ⚠️ Phase 20 ships in TWO PRs — 20a is merged, this is the second
The plan is `docs/phases/phase-20-plan.md`, approved and still current; 20b is its **§8 steps
4-8**. `docs/test-reports/phase-20a.md` records what the first half delivered.
**`phase-20-complete` is tagged only when THIS PR merges and verifies.** It is deliberately absent
now - `git tag --list "phase-2*"` returns nothing, and that is correct.

## Phase 20a merge verification (PASSED 2026-09-24, NO TAG by design)
PR #26 merged as merge commit 7e826ab (parents ddc86f9 + d8d775d). Branch is an ancestor of
`main`, no missing commits, no file diff, all 22 remote branches intact. CI on `main` green.
`./mvnw clean verify` on `main` -> **334 + 83**, 0 failures, 0 skipped, and the working tree stayed
CLEAN afterwards (the Phase 19 diagram-determinism fix still holding). `scripts/smoke-test.sh` on
`main` -> **270 passed, 0 failed, 0 skipped**. Migrations V11 and V12 present; `common/` and
`ecomdemo-app/` present.

## Where 20a left the code
- `product_stock` is its own table (V11), NO foreign key, a missing row reads as zero.
- `cart_item` snapshots productId/productName/unitPrice (V12); `fk_cart_item_product` is gone.
- The build is a reactor: parent + `common` (a library depending on NO other module) +
  `ecomdemo-app` (still the whole application, one deployable).
- TWO dependencies inverted or vanished, both as consequences rather than decisions:
  `inventory -> catalog` became `catalog -> inventory`, and `order -> catalog` is GONE - checkout
  reads no product at all, so order-service will not call catalog-service.
- A cart reflects the catalogue as at add-to-cart time, not today's. Pinned by a CartApiIT case.

## Checklist for 20b (plan §8 steps 4-8)
- [x] **Extract `inventory-service`** - DONE and committed (4278e0f). Its own module, its own
      `inventory_db` starting at Flyway V1, a REST API, and a Kafka publisher for stock changes.
      **inventory-service's own 20 tests pass.** `ecomdemo-app`'s test suite does NOT yet compile -
      that is the next commit.
- [ ] Extract `catalog-service` (takes `cache` with it - what it caches is the catalogue)
- [ ] Extract `inventory-service`
- [ ] Extract `customer-service` (customer + security + auth; it ISSUES tokens)
- [ ] Extract `notification-service`; `order-service` is what `ecomdemo-app` becomes
- [ ] A database per service, each with its own Flyway history starting at V1
- [ ] `RestClient` where the answer is needed now; Kafka where it is not. The outbox stays in
      order-service ONLY
- [ ] JWT VALIDATION in every service (decoder config into `common`); issuing stays in customer
- [ ] Compose: ~14 containers, **384M limit per service**, `kafka-ui` behind a `tools` profile
- [ ] Smoke test rebuilt against per-service ports, full flow still end to end
- [ ] Testing protocol in full + `docs/test-reports/phase-20b.md`
- [ ] README, docs/decisions.md, RECENT rotation (Phase 19 archived), tracker
- [ ] PR raised, and ONLY after it merges and verifies: tag `phase-20-complete`

## The constraint that shapes this, MEASURED
Docker Desktop is capped at **3.8 GB** on an 8 GB host. At idle a JVM costs **296 MiB** and
PostgreSQL **31 MiB** - so five databases cost ~125 MiB more than one while five JVMs cost ~1.2 GB
more. That is why database-per-service STAYS and the JVMs get capped instead; collapsing to one
database with a schema per service would save a rounding error and give up the phase's subject.
Projection ~2.0-2.4 GB. **A stack that will not start means no smoke verification at all** - this
is the open risk of 20b.

## Last test run
- 2026-09-24 (on `main`, 20a merge verification): `./mvnw clean verify` -> 334 + 83, 0 failures.
  `scripts/smoke-test.sh` -> 270 passed, 0 failed.

## Open issues / blockers
- KNOWN DEFECT from Phase 19, not fixed: two `EcomDemo Overview` stat panels mislead - `Orders
  placed / min` reads 0.00 for an hour containing 34 orders, and `Failed checkouts` shows a STALE
  value because its ratio goes NaN and `lastNotNull` skips nulls but not zeros. Agreed: its own
  `fix/dashboard-stat-reducers` branch. Full entry in `docs/decisions.md`.
- The Phase 15 manual check (Grafana render, Kafka UI) is CLOSED. Do not re-raise.

## Machine-level traps that have already cost time here
- **Docker Desktop quitting.** Testcontainers then fails with "Could not find a valid Docker
  environment", which reads like a code failure. `open -a Docker` and wait for `docker info`.
- **macOS denying access to the project directory** (TCC on ~/Documents): `ls` and reads return
  "Operation not permitted" while writes to new files still work. It cleared on its own.
- **Checking out a branch while Grafana runs** replaces the bind-mounted provisioning directory's
  inode; fix with `docker compose up -d --force-recreate grafana`.
- **`mvn test-compile` can report success against STALE test classes.** Use `clean` after any
  signature change.

## Decisions this phase — APPROVED, and now IMPLEMENTED for inventory
1. **Checkout reserves stock as a SAGA.** `OrderPlacementService.reserve` is
   `Propagation.MANDATORY` today precisely so stock cannot commit while the order rolls back. Over
   HTTP that guarantee is gone. Agreed shape: call reserve, and on failure compensate through an
   `AFTER_ROLLBACK` hook calling a new RELEASE endpoint. To be documented honestly rather than
   hidden: the compensating call can itself fail, so a crash between reserve and rollback leaks
   stock until something reconciles. A reconciliation job is Phase 21+ work, NOT this phase.
2. **`ProductStockChangedEvent` becomes a Kafka event** on a new `inventory.stock-changed` topic;
   whoever caches catalogue data subscribes. This is what the event always wanted to be - the
   Phase 16 comment on it already said "from Phase 17 the same fact is the natural thing to
   publish to Kafka". Dropping the eviction and relying on the TTL was rejected: that is exactly
   the defect Phase 16 was written to fix.

## The extraction ORDER was corrected before any code moved
The 20b housekeeping commit said catalog-service would go first, on the grounds that nothing
depends on it. That is the wrong test. What matters for an extraction is what the service NEEDS,
and catalog needs inventory (it asks for stock to fill ProductResponse). **inventory is the true
leaf** - it depends on nothing but `common` - so it goes first and is the only service that can be
extracted without writing an HTTP client in the same commit.

## Environment left behind
Docker Desktop running, the compose stack **UP** (nine containers), schema **V12**. The SonarQube
stack is stopped. `.env` holds a real JWT_SECRET and is gitignored.

## Next action
**ALL 314 UNIT TESTS ACROSS THE REACTOR ARE GREEN** (2c54db3). inventory-service is extracted and
holds 21 of them. What remains for this service before moving to catalog:

1. `./mvnw clean verify` - the INTEGRATION tests have not been run since the extraction. Expect
   failures: the *IT classes in ecomdemo-app start a full context and several will try to reach
   inventory-service over HTTP. Same judgement as the unit tests - mock the client where the test
   is about ordering, and move the claim where it is about stock.
2. Compose: `inventory-db` + `inventory-service`, **384M limit**, health check, `depends_on`, and
   `INVENTORY_BASE_URL=http://inventory-service:8082` for the app.
3. ONLY THEN is the smoke test meaningful again for this service.

A REAL BUG was found by the compensation test and is fixed: registerSynchronization sat AFTER the
reservation loop, so a failure DURING the loop - the likeliest failure - registered nothing and
released nothing. It is registered before the loop now, over a list the loop mutates.

### Superseded notes (kept for the remaining four services)
`ecomdemo-app`'s tests COMPILE now and four of its @SpringBootTest classes FAIL, all with
`ConnectException` to localhost:8082 - they create products, which PUTs stock to a service that is
not running. `./mvnw -pl common,inventory-service -am test` is green (21 tests).

**The remaining work is a judgement call per test, not a mechanical fix**, because two of them
assert something that has CHANGED MEANING rather than merely moved:

1. `ConcurrentCheckoutTest.twoThreadsBuyingTheLastUnit` - MOVED already to
   `ConcurrentReservationTest` in inventory-service. DELETE it here with a pointer comment; do not
   leave a weakened copy.
2. `ConcurrentCheckoutTest.theLosingCheckoutLeavesNoPartialData` - asserts the loser's stock
   reduction "rolls back with the rest of its transaction". **That is no longer what happens.**
   The reservation committed in another process; what un-does it is the saga's release() call. The
   test should assert the COMPENSATION: a rolled-back checkout calls release for every line it had
   already reserved. That is new behaviour with nothing covering it, and it is the single most
   valuable test to add in this phase.
3. `aRejectedCheckoutStillLeavesAnAuditRow`, `aSuccessfulCheckoutIsAuditedAgainstItsOrder`,
   `PlaceOrderFlowTest` - these are about ordering and auditing, not stock. `@MockitoBean
   InventoryClient` is the right answer: it lets them test what they still cover, and the stock
   half is covered in inventory-service.

Then `./mvnw clean verify` across the reactor, and only then move on to catalog-service.

REMEMBER: the smoke test is NOT a valid net from this commit onward - nothing runs inventory-service
yet, so `docker compose up` would start an app whose InventoryClient has nothing to call. Compose
work is step 5 of the list above. Do not run the smoke test and read its failures as regressions.

### Original design notes for the extraction (kept for the remaining four services)
Start with **inventory-service** (see the ordering correction above). The work is designed and the
two decisions it depends on are approved; a first attempt was made and DELIBERATELY PARKED rather
than left half-applied, so this branch is at the green housekeeping commit with a clean tree.

The five call sites that have to change, surveyed:

    order/internal/OrderPlacementService   InventoryService  -> HTTP + saga compensation
    catalog/ProductService                 InventoryService  -> HTTP (batched quantitiesFor)
    batch/ProductUpsertWriter              InventoryService  -> HTTP
    batch/ProductImportJobConfig           InventoryService  -> HTTP (wiring only)
    cache/ProductCacheEvictor              ProductStockChangedEvent -> Kafka consumer

Order of work, each commit leaving `./mvnw clean verify` green:
1. `inventory-service` module: pom, `InventoryServiceApplication`, move `com.ecomdemo.inventory`,
   `inventory_db` with its own Flyway history starting at **V1** (the product_stock DDL carried
   over verbatim from the monolith's V11 - the shape must not change, or the cutover becomes a
   data change as well as a deployment change).
2. Its REST API: read (single + BATCH, because a listing must not become N HTTP calls), set level,
   reserve, release.
3. An `InventoryClient` in `ecomdemo-app` behind the same method shapes the callers already use,
   so the five sites change their wiring and not their logic.
4. `inventory.stock-changed` published by the service, consumed where the cache lives.
5. Compose: `inventory-db` + `inventory-service`, **384M limit**, health check, `depends_on`.

WHAT IS NOT TRUE YET: no service is extracted, `ecomdemo-app` is still the whole application, and
the smoke test still passes at 270 because nothing has moved. The moment step 1 lands, the smoke
test stops being a valid net until step 7 rebuilds it per service - that is the known cost of this
half and the reason 20a exists.

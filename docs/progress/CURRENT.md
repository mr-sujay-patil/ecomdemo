# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-24
- **Phase:** 20b: Microservices Split - EXTRACTION (the second half of Phase 20)
- **Branch:** feature/phase-20b-microservices
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #27 — https://github.com/mr-sujay-patil/ecomdemo/pull/27
- **Waiting for user:** YES — review the PR, and decide the scope question in §8 of the test report

## 🟢 RESUME HERE (written 2026-09-24 at a clean stop; read this first)

**Nothing is half-done.** HEAD is `6571ecf` on `feature/phase-20b-microservices`, the working tree
is CLEAN, everything is pushed, and PR #27 is open with **CI passing**. There is no in-flight edit,
no uncommitted experiment and no failing test to chase. The phase is at a legitimate stop point
waiting on a human.

**What the user has to do, not you:** review PR #27, and answer the scope question below. Until they
do, the correct action is to WAIT.

**The scope question** (full argument in `docs/test-reports/phase-20b.md` §8): this PR extracts ONE
of the five services the plan named. The recommendation is **one service per PR** from here, because
extracting the first produced four failures that `./mvnw clean verify` could not see (listed further
down under "What got past clean verify"). The roadmap already has a `20c` row for the remaining
services. If the user instead wants all four in this phase, continue on this branch.

**When the user says `approved, merge it`:** `gh pr merge 27 --merge` — a merge commit, never squash
or rebase, and NEVER `--delete-branch`. Then run merge verification per
`docs/process/execution-protocol.md` §5.

**DO NOT TAG `phase-20-complete` after merging this PR.** Phase 20 is complete when the LAST service
is extracted, not this one. `git tag --list "phase-2*"` returning nothing is correct.

**Environment as left:** 11 containers were still UP and healthy when the session ended. They may or
may not still be running. Nothing depends on them — bring them back with
`docker compose up -d --wait` (images are already built), or stop them with `docker compose down`
(which KEEPS both database volumes; only `-v` deletes them).

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
- [x] **JWT VALIDATION in every service** - DONE (f46172d). `com.ecomdemo.jwt` in `common` holds
      the key, the decoder and the roles converter; the encoder stayed in the app. inventory-service
      has its own `SecurityConfig`, and the app calls it as ITSELF with a SERVICE token.
- [x] **Compose** - `inventory-db` + `inventory-service` up and healthy, 384M limit, `kafka-ui`
      behind the `tools` profile, Prometheus scraping both services as one job.
- [x] **Smoke test valid again across two services** — 270 passed, 0 failed, run TWICE. Two checks
      changed meaning, one changed how it measures; none added, removed or weakened.
- [x] **Testing protocol in full** + `docs/test-reports/phase-20b.md`
- [x] **README, docs/decisions.md, RECENT rotation (Phase 19 archived), tracker**
- [ ] PR raised, and ONLY after it merges and verifies: tag `phase-20-complete`

## The constraint that shapes this, MEASURED
Docker Desktop is capped at **3.8 GB** on an 8 GB host. At idle a JVM costs **296 MiB** and
PostgreSQL **31 MiB** - so five databases cost ~125 MiB more than one while five JVMs cost ~1.2 GB
more. That is why database-per-service STAYS and the JVMs get capped instead; collapsing to one
database with a schema per service would save a rounding error and give up the phase's subject.
Projection ~2.0-2.4 GB. **A stack that will not start means no smoke verification at all** - this
is the open risk of 20b.

## Last test run (2026-09-24, this branch, FINAL — all green, nothing outstanding)
- **CI on PR #27: `Build and test` PASS (2m56s).** `Publish image to GHCR` skipped, as it does on
  every PR; it runs on merge to main.
- `./mvnw clean verify` -> **5 + 34 + 317 + 81**, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.
  The working tree stayed CLEAN afterwards, so the Phase 19 diagram determinism still holds.
- `scripts/smoke-test.sh` -> **270 passed, 0 failed, 0 skipped**, run TWICE against 11 healthy
  containers. Cache eviction converges on the first poll (`took 0ms`).
- Every new test was MUTATION-CHECKED: broken deliberately, observed to fail, restored.

## Next action
**WAIT FOR THE USER.** The PR is open and the phase is at a stop point. Do not start the next
extraction, and DO NOT TAG `phase-20-complete` - Phase 20 is not complete.

There is a scope decision for the user, set out in `docs/test-reports/phase-20b.md` §8: this PR
extracts ONE of five services, and the recommendation is one service per PR from here. The evidence
is that extracting the first produced FOUR failures that `./mvnw clean verify` could not see, each
found only by running the stack. Three of the four are now build failures for the next service,
which is most of the value of having paid for them once.

If the user approves that split, the roadmap already has a `20c` row for the remaining services.
If the user wants all four in this phase instead, the branch continues from here.

## Open issues / blockers
- KNOWN DEFECT from Phase 19, not fixed: two `EcomDemo Overview` stat panels mislead - `Orders
  placed / min` reads 0.00 for an hour containing 34 orders, and `Failed checkouts` shows a STALE
  value because its ratio goes NaN and `lastNotNull` skips nulls but not zeros. Agreed: its own
  `fix/dashboard-stat-reducers` branch. Full entry in `docs/decisions.md`.
- The Phase 15 manual check (Grafana render, Kafka UI) is CLOSED. Do not re-raise.

## What got past `./mvnw clean verify` this phase (read before extracting the next service)
Four failures, none of them logic errors, each a configuration correct for one service and silently
wrong for two. All four now have tests; three of those tests fail automatically for the NEXT
service, which is the point.
1. **No filter chain** -> Boot secured everything with a generated password. The service reported
   itself HEALTHY throughout, because the health probe is the one path the fallback leaves open.
2. **The new database was empty.** The extraction moved the table and left the DATA behind, and a
   missing stock row reads as zero BY DESIGN - so ten products silently showed zero stock.
3. **A GLOBAL `spring.json.value.default.type`** made the stock event deserialise as an
   OrderPlacedEvent. **The consumer group showed ZERO LAG the whole time** - consumed, failed,
   retried, exhausted, dropped, offset advancing exactly as it does when healthy.
4. **Alloy shipped no logs** for the new containers; its keep rule named `app` only. A service that
   ships no logs and one that does not exist look identical from Loki.

## Machine-level traps that have already cost time here
- **Docker Desktop quitting.** Testcontainers then fails with "Could not find a valid Docker
  environment", which reads like a code failure. `open -a Docker` and wait for `docker info`.
- **macOS denying access to the project directory** (TCC on ~/Documents): `ls` and reads return
  "Operation not permitted" while writes to new files still work. It cleared on its own.
- **Checking out a branch while Grafana runs** replaces the bind-mounted provisioning directory's
  inode; fix with `docker compose up -d --force-recreate grafana`.
- **`mvn test-compile` can report success against STALE test classes.** Use `clean` after any
  signature change.
- **Do not `docker compose up --build` while the stack is running.** Two Maven builds inside a
  3.8 GB Docker VM starve it: Kafka, Grafana and Prometheus all went unhealthy and the build hung
  for twenty minutes. `docker compose down` first, or `build` then `up`.
- **`./mvnw compile` alone now fails** on ecomdemo-app: it needs `common`'s test-jar, which is
  produced at the `package` phase. `test-compile`, `test` and `verify` are all fine.
- **A freshly restarted app makes the Prometheus smoke check flake once**: `rate(...[5m])` needs
  two samples, and a counter series created seconds ago has one. Re-run; it is not a regression.

## Decisions taken while implementing (not in the plan, recorded in docs/decisions.md)
- **The app calls inventory-service as ITSELF, not as the shopper.** Token relay cannot work for
  two callers: the anonymous product listing has no token, and the CSV import runs on a background
  thread with no SecurityContext. The cost is that inventory-service cannot tell an administrator
  from a shopper, so AUTHORISATION stays at the edge - which is why two `@PreAuthorize` rules were
  removed from `InventoryController` rather than translated.
- **HS256 with a shared secret is KEPT, knowingly.** Every service now holds a key that can mint as
  well as verify. The fix is asymmetric keys plus a JWKS endpoint, which is a phase of its own.

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

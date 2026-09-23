# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-23
- **Phase:** 20: Microservices Split (multi-service architecture)
- **Branch:** feature/phase-20-microservices
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO — the plan (`docs/phases/phase-20-plan.md`) was APPROVED 2026-09-23.

## Phase 19 merge verification (PASSED 2026-09-23)
PR #24 merged as 6e96292, then follow-up PR #25 as ddc86f9 (parents 6e96292 + 672721d) for the two
defects the verification itself found: non-deterministic generated diagrams, and a smoke check that
had become a race when Phase 18's outbox put a 1s relay poll in the path. Branch is an ancestor of
`main`, no missing commits, no file diff, all 21 remote branches intact. CI on `main` green.
`./mvnw clean verify` on `main` -> **319 + 82**, 0 failures, 0 skipped, and the working tree stayed
CLEAN afterwards, which is the determinism fix proving itself. `scripts/smoke-test.sh` -> **270
passed, 0 failed**. Tag `phase-19-complete` pushed at ddc86f9.

## The memory ceiling, MEASURED (this decides the architecture)
Host **8 GB**; Docker Desktop capped at **3.8 GB** - which is why nine containers OOM-killed a
background task earlier. Measured at idle: a JVM is **296 MiB**, PostgreSQL is **31 MiB**,
kafka-ui 242, kafka 241, grafana 199, loki 59, alloy 42, prometheus 32, redis 9. Total ~1.15 GB.
**Five databases cost ~125 MiB more; five JVMs cost ~1.2 GB more.** So collapsing to one database
with a schema per service would save a rounding error and give up the phase's whole subject -
database-per-service STAYS. The levers are a 384M cap per service and putting kafka-ui behind a
Compose profile (~400 MiB back). Projection ~2.0-2.4 GB of 3.8 GB.

## Checklist (copied from the phase's "What you'll implement")
- [ ] Services: catalog, inventory, order (cart + orders + outbox + reports), customer (users +
      JWT), notification
- [ ] A database per service, each with its own Flyway migrations
- [ ] Synchronous calls through RestClient / HTTP Interface clients; async through Kafka
- [ ] JWT validation in each service
- [ ] All services in Compose
- [ ] Done when: the full purchase flow works across services
- [ ] Smoke test: runs against the individual services' ports, full flow still works end to end
- [ ] Testing protocol run in full + docs/test-reports/phase-20.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 18 archived),
      tracker -> 🔵
- [ ] PR raised

## The two changes that carry the risk (see the plan for detail)
1. `catalog` and `inventory` SHARE the `product` table. Phase 19 split the behaviour and left the
   table, recording that Phase 20 is where it comes due. Splitting it touches the Phase 12
   optimistic lock, the Phase 16 cache eviction, the CSV import and the checkout reservation.
2. `CartItem` has a real FK to `product` and holds `@ManyToOne Product`. Cart goes to
   order-service, product to catalog-service - the FK cannot survive. It becomes what `OrderItem`
   already is (id + snapshotted name and price), which is a real behaviour change: the cart stops
   reflecting today's catalogue and starts reflecting the catalogue as at add-to-cart time.
Both are planned to happen INSIDE the monolith first (plan steps 2 and 3), while the full
270-check smoke test still applies end to end.

## Last test run
- 2026-09-23 (step 2 complete): `./mvnw clean verify` -> **334 + 82**, 0 failures, 0 skipped,
  1m23s. `scripts/smoke-test.sh` -> **270 passed, 0 failed, 0 skipped - UNCHANGED**, which is the
  whole point of doing the data split inside the monolith.

## Open issues / blockers
- The Phase 15 manual check (Grafana render, Kafka UI) is CLOSED - verified by the user on
  2026-09-23. Do not re-raise.
- KNOWN DEFECT carried from Phase 19, not fixed: two `EcomDemo Overview` stat panels mislead
  (`Orders placed / min` reads 0.00 for an hour containing 34 orders; `Failed checkouts` shows a
  STALE value because its ratio goes NaN and `lastNotNull` skips nulls but not zeros). Full entry
  in `docs/decisions.md`. Agreed: fix on its own `fix/dashboard-stat-reducers` branch.
- Grafana traps that cost time during Phase 19 verification and will again: (a) checking out a
  branch while Grafana runs replaces the bind-mounted provisioning dir's inode - fix with
  `docker compose up -d --force-recreate grafana`; (b) the admin password persists on the
  `grafana-data` volume and repeated 401s trip a 5-minute brute-force lockout.

## Progress against the plan's §8 order
NOTE: steps 2 and 3 are being done BEFORE step 1, stated to the user and agreed. The Maven
restructure buys nothing for the two data changes, and doing them while the build is untouched
keeps the 270-check net at its most trustworthy.

- [x] **Step 2 - split product_stock out of product.** V11 migration, no foreign key (the tables
      are about to be in different databases), missing row reads as zero. THE DEPENDENCY INVERTED:
      InventoryService deals in product ids and never sees a Product, so `inventory -> catalog` is
      gone and `catalog -> inventory` replaces it. The optimistic lock moved with the column.
      StockMutationRulesTest now names ONE module, which is exactly what Phase 19 predicted.
      The CSV import gained `ImportedProduct` because a processor cannot set stock on an unsaved
      product. `./mvnw clean verify` -> 334 + 82, 0 failures. Smoke -> **270 passed, unchanged**.
- [x] **Step 3 - CartItem drops its FK to product**, snapshots name and price. V12 backfills
      through the join that is about to disappear, then drops the constraint. ORDER NO LONGER
      DEPENDS ON CATALOG AT ALL - checkout builds the line and the reservation from the cart's
      snapshot, so order-service will place an order without calling catalog-service. Removing the
      FK also removed a query (CartRepository's second JOIN FETCH). BEHAVIOUR CHANGE: a cart now
      reflects the catalogue as at add-to-cart time, pinned by a new CartApiIT test.
      `./mvnw clean verify` -> 334 + 83, 0 failures. Smoke -> **270 passed, unchanged**.
- [ ] Step 1 - Maven multi-module skeleton (parent + common).
- [ ] Steps 4-7 - extract the five services, compose, rebuild the smoke test per service.

## Decisions this phase (to be copied into docs/decisions.md)
- product_stock is a separate table with NO foreign key to product, because the two are about to
  be in different databases; a missing row means zero.
- The optimistic lock moved to product_stock, which sharpens it: it used to make a checkout
  collide with an administrator editing a description.
- InventoryService takes the product NAME as a parameter for its error message, because it can no
  longer look one up. The first small tax of the split, and the honest one to pay.
- ProductResponse takes stock as a PARAMETER so the lookup stays batched - one call for a listing,
  not one per row, which becomes N+1 HTTP round trips after the split.
- CartItem snapshots productId/productName/unitPrice instead of holding @ManyToOne Product. The
  cart stops reflecting today's catalogue and starts reflecting it as at add-to-cart time; that is
  the trade, and CartApiIT pins it.
- Incremental `mvn test-compile` can report success against STALE test classes. Use
  `mvn clean test-compile` when a signature changes, or the tests appear to compile when they
  cannot.
- @MockitoBean does NOT work for ApplicationEventPublisher (Spring resolves it as the context
  itself, not a bean). @RecordApplicationEvents is the right tool and tests the real publication.

## Environment left behind
Docker Desktop running; the compose stack is **UP** (nine containers), schema now **V11**.
The SonarQube stack is stopped. `.env` holds a real JWT_SECRET and is gitignored.

## Next action
Both data changes are DONE and green, which was the whole reason for doing them first. What
remains is structural:

1. **Step 1 - Maven multi-module skeleton**: parent POM, a `common` library module (shared,
   logging, metrics, cache), the monolith still running as one deployable. Touches the build
   (JaCoCo, Surefire/Failsafe, Sonar, the Dockerfile, CI), not behaviour.
2. **Steps 4-6** - extract catalog-service and inventory-service, then customer-service and
   notification-service, leaving order-service; HTTP clients between them; JWT validation in each;
   five databases; compose with per-service memory limits and kafka-ui behind a profile.
3. **Step 7** - rebuild the smoke test against per-service ports.
4. Testing protocol in full, test report, README, decisions, RECENT rotation, PR.

MEMORY: cap each service at 384M. A JVM idles at ~296 MiB and Docker Desktop has 3.8 GB total;
five uncapped JVMs against that cgroup is the mistake compose.yaml already warns about, five
times over.

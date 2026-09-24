# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-24
- **Phase:** 20b: Microservices Split - EXTRACTION (the second half of Phase 20)
- **Branch:** feature/phase-20b-microservices
- **Step:** BRANCHED
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

## Decisions this phase
- (none yet)

## Environment left behind
Docker Desktop running, the compose stack **UP** (nine containers), schema **V12**. The SonarQube
stack is stopped. `.env` holds a real JWT_SECRET and is gitignored.

## Next action
Start step 5 (IMPLEMENTING) on plan §8 step 4: extract `catalog-service` first, because it is the
one whose dependencies already point outward - 20a removed both edges INTO it from order and
inventory, so it can leave with `cache` and take nothing else with it. Give it its own module, its
own database and its own Flyway history, then have `ecomdemo-app` reach it over `RestClient`.

Work one service per commit, running `./mvnw clean verify` after each. The smoke test cannot be
trusted again until step 7 rebuilds it against per-service ports - that is the known cost of this
half, recorded in the 20a report.

# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 6: Transactions & Concurrency (@Transactional + optimistic locking)
- **Branch:** feature/phase-06-transactions
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 05 merge verification (passed 2026-09-22)
PR #5 MERGED with a merge commit (67d3453, 2 parents: 1e7818d + 2a05028); branch is an ancestor of
`main`; no commits and no file diffs between branch and `main`; local and remote branches intact;
every "What you'll implement" item present in `main` (`db/migration/V1__init_schema.sql`,
`V2__seed_products.sql`, `V3__add_product_category.sql`, `ddl-auto=validate` in both
`application.properties` and `application-test.properties`, the Flyway settings, the smoke test's
migration/category section, `docs/test-reports/phase-05.md`);
`./mvnw clean verify` on `main` -> BUILD SUCCESS, 108 tests, 0 failures; the app on `main` started
with "Successfully validated 3 migrations" / "Current version of schema public: 3";
`scripts/smoke-test.sh` -> 65 passed, 0 failed, 0 skipped, 0 ERROR in the app log (only springdoc's
2 advisory WARNs); tag `phase-05-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] An atomic place-order: any stock failure rolls back everything
- [x] `@Transactional(readOnly = true)` on read services
- [x] `@Version` on `Product`; handle `OptimisticLockingFailureException` with a limited retry
      and a 409 response
- [x] An `order_audit` table written with `Propagation.REQUIRES_NEW`
- [x] A concurrency test: two threads buy the last unit, exactly one succeeds
- [ ] Smoke test addition: stock set to 1, two parallel orders -> one success, one 409, stock 0
- [ ] Testing protocol run in full + docs/test-reports/phase-06.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 04 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, Tests run: 120, Failures: 0, Errors: 0
  (12 new: 4 in `ConcurrentCheckoutTest`, 3 added to `OrderPlacementServiceTest`, 4 retry tests
  replacing 6 moved ones in `OrderServiceTest`, 1 in `FlywayMigrationTest`). Run three times in a
  row, all green.
- 2026-09-22: `ConcurrentCheckoutTest` alone, 10 consecutive runs, all green; the log shows the
  versioned UPDATE losing in both race tests ("Unexpected row count (expected 1 but was 0) ...
  where id=? and version=?").

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- The retry loop and the transactional unit of work are SEPARATE beans (`OrderService` ->
  `OrderPlacementService`). Self-invocation would bypass the proxy and run every attempt with no
  transaction at all; it also has to be a new transaction per attempt, because a failed flush
  leaves the persistence context unusable and the transaction rollback-only.
- `order_audit` has NO foreign key to `orders`: written with REQUIRES_NEW, it commits while the
  order's own insert is still uncommitted, and a REJECTED row names no order at all.
- The per-line stock pre-check stays even though the transaction would undo a partial reduction:
  it produces the accurate "3 requested, 2 available" message and saves work the database would
  only throw away.
- `CartService` is read-WRITE throughout, `view()` included: the shared cart row is created on
  first use, and readOnly=true would put Hibernate in manual flush mode and silently drop it.
- `ProductService` defaults to readOnly at class level with the four writers overriding it, so a
  new read method is safe by default and a new write fails loudly if it forgets.
- `FlywayMigrationTest` now scopes its catalogue assertions to V2's ten seeded names: every
  `@SpringBootTest` shares one H2 database and the new concurrency test creates products of its
  own (and deletes them again in `@AfterEach`).

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at v3.
`docker start ecomdemo-postgres` if it is down. The application itself is stopped.

## Next action
Start step 5 (IMPLEMENTING): read the current order/cart/product services, then work the checklist
top to bottom with small Conventional Commits, ticking each item here as it lands.

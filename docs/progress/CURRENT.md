# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 6: Transactions & Concurrency (@Transactional + optimistic locking)
- **Branch:** feature/phase-06-transactions
- **Step:** BRANCHED
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
- [ ] An atomic place-order: any stock failure rolls back everything
- [ ] `@Transactional(readOnly = true)` on read services
- [ ] `@Version` on `Product`; handle `OptimisticLockingFailureException` with a limited retry
      and a 409 response
- [ ] An `order_audit` table written with `Propagation.REQUIRES_NEW`
- [ ] A concurrency test: two threads buy the last unit, exactly one succeeds
- [ ] Smoke test addition: stock set to 1, two parallel orders -> one success, one 409, stock 0
- [ ] Testing protocol run in full + docs/test-reports/phase-06.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 04 archived), tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22 (on `main`, merge verification): `./mvnw clean verify` -> BUILD SUCCESS, 108 tests,
  0 failures; `scripts/smoke-test.sh` -> 65 passed / 0 failed / 0 skipped.

## Open issues / blockers
- none

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet

## Environment left behind
Docker container `ecomdemo-postgres` (postgres:18-alpine, port 5432) is RUNNING, schema at v3.
`docker start ecomdemo-postgres` if it is down. The application itself is stopped.

## Next action
Start step 5 (IMPLEMENTING): read the current order/cart/product services, then work the checklist
top to bottom with small Conventional Commits, ticking each item here as it lands.

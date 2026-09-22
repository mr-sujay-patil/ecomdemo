# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 17: Messaging (Apache Kafka, KRaft)
- **Branch:** feature/phase-17-kafka
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 16 merge verification (passed 2026-09-22)
PR #18 merged as a merge commit (e407627, parents 77a9d13 + 1313a6e), plus follow-up PR #19
(dc35a85) for two defects the verification itself found: Alloy's health check ran under dash,
which has no `/dev/tcp`, and the infrastructure log check used Loki's default label window.
Branch is an ancestor of `main`, no missing commits, no file diffs, all branches intact.
CI on `main` green. `./mvnw clean verify` on `main` -> 269 + 70, 0 failures, 0 skipped.
`scripts/smoke-test.sh` -> 228 passed, 0 failed, 0 skipped. Tag `phase-16-complete` pushed.

## The Phase 13 cache defect is CLOSED (PR #20, merged c9fa121)
Accepted as a known defect during Phase 16, then fixed on the user's instruction before Phase 17.
A checkout now evicts `product::<id>` and `productList::all` through
`@TransactionalEventListener(AFTER_COMMIT)`; `ProductService.save` publishes
`ProductStockChangedEvent` and `cache/ProductCacheEvictor` acts on it. Merge verification passed:
merge commit c9fa121 (2 parents), no diff, CI green, `./mvnw clean verify` on `main` -> **273 +
73**, 0 failures, 0 skipped, `scripts/smoke-test.sh` -> **234 passed, 0 failed, 0 skipped**.
No tag - a fix branch is not a phase. Report: `docs/test-reports/fix-product-cache-eviction.md`.

## Checklist (copied from the phase's "What you'll implement")
- [ ] Kafka (single broker, KRaft) and Kafka UI in Compose
- [ ] An `OrderPlacedEvent` published to `orders.placed`, keyed by order id
- [ ] A `notification` consumer that writes a log line and a `notifications` row
- [ ] Retry topics with backoff and a dead-letter topic
- [ ] An idempotent consumer backed by a `processed_events` table
- [ ] A Testcontainers Kafka test
- [ ] Done when: each order produces exactly one notification, and poison messages land in the DLT
- [ ] Smoke test additions: after placing an order, exactly one notification row exists for it,
      and the event is on the `orders.placed` topic
- [ ] Testing protocol run in full + docs/test-reports/phase-17.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 15 archived),
      tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22 (merge verification on `main`): `./mvnw clean verify` -> 273 + 73, 0 failures,
  0 skipped, 4m58s. `scripts/smoke-test.sh` -> 234 passed, 0 failed, 0 skipped.

## Open issues / blockers
- Carried from Phases 15-16: the Grafana dashboards' RENDER has still never been looked at by
  human eyes (all data behind them is verified). Chrome's site permissions block localhost:3000
  for browser automation here. Steps: `docs/test-reports/phase-15.md` §8 and the "EcomDemo Logs"
  dashboard with a correlation ID pasted into its textbox.

## Decisions this phase (copy to docs/decisions.md before the PR)
- (none yet)

## Environment left behind
Docker Desktop RUNNING. Seven containers up and healthy (`ecomdemo-app`, `-db`, `-cache`,
`-prometheus`, `-grafana`, `-loki`, `-alloy`), schema v8, image built from `main`. SonarQube
stack also up at http://localhost:9000; stop it with `docker compose -f compose.sonar.yaml down`
if the memory is wanted back. `.env` holds a real JWT_SECRET and is gitignored.

## Next action
Start IMPLEMENTING Phase 17 on `feature/phase-17-kafka`, first checklist item first: Kafka in
KRaft mode plus Kafka UI in `compose.yaml`. Read `docs/phases/phase-17-kafka.md` for the scope
and `docs/process/testing-protocol.md` before the PR. Nothing is waiting on the user.

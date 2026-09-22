# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 18: Reliable Event Publishing (Transactional Outbox)
- **Branch:** feature/phase-18-outbox
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 17 merge verification (PASSED 2026-09-22)
PR #21 merged as merge commit 68769a6 (parents c9fa121 + 90d5361), then follow-up PR #22 merged
as a70537f (parents 68769a6 + c7450e3) for the defect the verification itself found: the Loki
wait loop in the smoke test broke at `-gt 0` lines while the next check asserts `-ge 2`, so a cold
stack whose two requests land in separate Alloy batches failed it. Branch is an ancestor of
`main`, no missing commits, no file diff, all 19 remote branches intact. CI on `main` green at
a70537f. `./mvnw clean verify` on `main` -> **278 + 77**, 0 failures, 0 skipped.
`scripts/smoke-test.sh` on `main` -> **252 passed, 0 failed** warm, and **252 passed, 0 failed**
again after `docker compose restart loki alloy`, which is the cold path that caught the defect.

⚠️ TAG INCONSISTENCY, left for the user to decide. `phase-17-complete` points at 68769a6, the
phase PR's merge, NOT at a70537f, the follow-up's. `phase-16-complete` points at ITS follow-up
(dc35a85, PR #19), so the two phases are tagged by different rules. Moving a pushed tag needs a
force-push, which hard rule 6 forbids, so it was left alone and reported instead. The tag was also
already on origin when the previous session ended, created 22:09 against a 21:40 merge while
CURRENT.md still read PR_OPEN - so it was verified from scratch here, not trusted.

## Checklist (copied from the phase's "What you'll implement")
- [ ] An `outbox_events` table
- [ ] Checkout saves the order and the outbox row in ONE transaction
- [ ] A scheduled relay publishes pending rows and marks them as sent
- [ ] A demo with Kafka down
- [ ] A cleanup job for old rows
- [ ] Done when: no events are lost while Kafka is down
- [ ] Smoke test additions: stop Kafka, place an order (it succeeds), start Kafka, confirm the
      notification appears within a timeout
- [ ] Testing protocol run in full + docs/test-reports/phase-18.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 16 archived),
      tracker -> 🔵
- [ ] PR raised

## What this phase exists to fix (measured in Phase 17, not assumed)
Phase 17 publishes AFTER_COMMIT, which leaves the dual-write window open: the order commits, then
the send happens separately. With Kafka stopped, Phase 17's failure test lost **4 orders their
notification, permanently**. That number is the baseline this phase has to drive to zero.

## Last test run
- 2026-09-22 (on `main`, merge verification): `./mvnw clean verify` -> 278 + 77, 0 failures,
  0 skipped, 6m43s. `scripts/smoke-test.sh` -> 252 passed, 0 failed, twice.

## Open issues / blockers
- Carried from Phases 15-16: the Grafana dashboards' RENDER has still never been looked at by
  human eyes (all data behind them is verified). Chrome's site permissions block localhost:3000
  for browser automation here. Steps: `docs/test-reports/phase-15.md` §8 and the "EcomDemo Logs"
  dashboard with a correlation ID pasted into its textbox.
- Kafka UI at http://localhost:8090 is also still unseen by the user (Phase 17).

## Decisions this phase
- (none yet)

## Environment left behind
Docker Desktop RUNNING. NINE containers up (`ecomdemo-app`, `-db`, `-cache`, `-prometheus`,
`-grafana`, `-loki`, `-alloy`, `-kafka`, `-kafka-ui`), schema **v9**. The SonarQube stack is
STOPPED; `docker compose -f compose.sonar.yaml up -d` brings it back. `.env` holds a real
JWT_SECRET and is gitignored.

A stray `ecomdemo-postgres` container from 2026-09-21, outside `compose.yaml`, was holding port
5432 and blocking `docker compose up`. It was STOPPED, not removed: its data is intact and
`docker start ecomdemo-postgres` restores it, though nothing in this project wants it. Three
abandoned Testcontainers containers were removed. An untracked `handoff.md` (a pasted Phase 15
session transcript) sits in the working tree, unstaged and unused - not this session's file, so
it was left alone.

## Next action
Start step 5 (IMPLEMENTING): the `outbox_events` table as migration **V10**, then the write side
(checkout saves order + outbox row in one transaction), then the relay, then cleanup.
Work the checklist above top to bottom, one small Conventional Commit per item, ticking this file
as each lands.

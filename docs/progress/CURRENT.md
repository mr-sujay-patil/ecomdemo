# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 18: Reliable Event Publishing (Transactional Outbox)
- **Branch:** feature/phase-18-outbox
- **Step:** IMPLEMENTING
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
- [x] An `outbox_events` table
- [x] Checkout saves the order and the outbox row in ONE transaction
- [x] A scheduled relay publishes pending rows and marks them as sent
- [x] A demo with Kafka down
- [x] A cleanup job for old rows
- [x] Done when: no events are lost while Kafka is down
- [x] Smoke test additions: stop Kafka, place an order (it succeeds), start Kafka, confirm the
      notification appears within a timeout - 18 new checks, all passing
- [ ] Testing protocol run in full + docs/test-reports/phase-18.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 16 archived),
      tracker -> 🔵
- [ ] PR raised

## What this phase exists to fix (measured in Phase 17, not assumed)
Phase 17 publishes AFTER_COMMIT, which leaves the dual-write window open: the order commits, then
the send happens separately. With Kafka stopped, Phase 17's failure test lost **4 orders their
notification, permanently**. That number is the baseline this phase has to drive to zero.

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, Surefire **310** (was 278) + Failsafe **82**
  (was 77), 0 failures, 0 skipped, 8m24s.
- 2026-09-22: `scripts/smoke-test.sh` -> **270 passed** (was 252), 0 failed, 0 skipped.
- 2026-09-22: THE OUTAGE, for real, in the smoke test: Kafka stopped -> checkout returned 201,
  the event sat in the outbox, the relay recorded its failed attempts on the row; Kafka started
  -> the order was notified with nobody replaying anything. Phase 17 lost 4 orders under this
  same test. This phase loses **zero**.
- 2026-09-22: A REGRESSION the smoke test caught and the other two suites did not - see the
  decisions below. Both were green while the consumer's whole partition was stopped.

## Open issues / blockers
- Carried from Phases 15-16: the Grafana dashboards' RENDER has still never been looked at by
  human eyes (all data behind them is verified). Chrome's site permissions block localhost:3000
  for browser automation here. Steps: `docs/test-reports/phase-15.md` §8 and the "EcomDemo Logs"
  dashboard with a correlation ID pasted into its textbox.
- Kafka UI at http://localhost:8090 is also still unseen by the user (Phase 17).

## Decisions this phase (to be copied into docs/decisions.md)
- The event is written to `outbox_event` in the ORDER's transaction; `OutboxWriter` is
  `Propagation.MANDATORY` so it cannot accidentally open one of its own and re-create the dual
  write behind code that looks correct.
- Two identifiers per row: a BIGINT sequence for publication ORDER, a UUID for the idempotency
  key the consumer matches. Neither can do the other's job.
- `published_at IS NULL` is the entire state machine - a nullable timestamp cannot contradict
  itself the way a `status` column can.
- The payload is serialised at WRITE time, with the CONSUMER's mapper
  (`JacksonUtils.enhancedObjectMapper()`, Jackson 2), not Boot 4's Jackson 3 - the two disagree
  about how an Instant is written and nothing in the build would catch it.
- The relay waits for the broker's ACK before marking a row published, and stops the batch at the
  first failure (ordering, and not spending max.block.ms on every remaining row).
- No maximum attempt count: the failure this is built for is "the broker is unreachable", and
  giving up after N would lose the events the phase exists to keep.
- The cleanup sweeps only PUBLISHED rows. A sweep by age alone would delete pending events
  precisely because an outage made them old.
- The relay's producer is NOT a KafkaTemplate bean - see the regression above.
- SELECT ... FOR UPDATE SKIP LOCKED is DEFERRED, not solved: two instances would both publish,
  and the consumer's idempotency absorbs it. Recorded as a known limitation.

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
Implementation and the full testing protocol are DONE and green. Remaining, in order:
1. `docs/test-reports/phase-18.md` (including the regression, in full).
2. README section, `docs/decisions.md` entries, RECENT.md rotation (Phase 16 archived).
3. Push and raise the PR, then STOP for review.

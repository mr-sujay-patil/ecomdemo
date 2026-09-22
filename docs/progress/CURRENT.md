# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 17: Messaging (Apache Kafka, KRaft)
- **Branch:** feature/phase-17-kafka
- **Step:** PR_OPEN (follow-up PR #22, on the same branch)
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #21 MERGED (68769a6). Follow-up #22 open for the defect merge verification found.
- **Waiting for user:** YES — review and merge the follow-up PR

## Phase 17 merge verification (2026-09-22)
PR #21 merged as a merge commit 68769a6 (parents c9fa121 + 90d5361). Branch is an ancestor of
`main`, no missing commits, no file diff, all 19 remote branches intact. CI on `main` green.
`./mvnw clean verify` on `main` -> 278 + 77, 0 failures, 0 skipped.
`scripts/smoke-test.sh` on `main` -> run 1: **251 passed, 1 failed**; run 2: 252 passed, 0 failed.
The tag `phase-17-complete` was ALREADY on origin when this session started (created 22:09, after
the 21:40 merge) while this file still read PR_OPEN — the previous session tagged without
checkpointing. Verification was re-run in full rather than assumed.

The one failure is a defect in the smoke script, not the application: the Loki wait loop at
`scripts/smoke-test.sh:1551` broke at `-gt 0` lines while the next check asserts `-ge 2`, so a
cold stack whose two requests land in separate Alloy batches fails it. Fixed on
`feature/phase-17-kafka` (execution-protocol §5: never fix on `main`) and raised as PR #22.

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
- [x] Kafka (single broker, KRaft) and Kafka UI in Compose
- [x] An `OrderPlacedEvent` published to `orders.placed`, keyed by order id
- [x] A `notification` consumer that writes a log line and a `notifications` row
- [x] Retry topics with backoff and a dead-letter topic
- [x] An idempotent consumer backed by a `processed_events` table
- [x] A Testcontainers Kafka test
- [x] Done when: each order produces exactly one notification, and poison messages land in the DLT
- [x] Smoke test additions: after placing an order, exactly one notification row exists for it,
      and the event is on the `orders.placed` topic - 18 new checks
- [x] Testing protocol run in full + docs/test-reports/phase-17.md
- [x] README section, docs/decisions.md entries (13), RECENT.md rotation (Phase 15 archived),
      tracker -> 🔵
- [x] PR raised (#21) — MERGED
- [x] Follow-up PR #22: the Loki wait loop waits for both requests, not merely the first

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, Surefire 278 (was 273) + Failsafe 77
  (was 73), 0 failures, 0 skipped, 1m55s.
- 2026-09-22: `./mvnw clean test` -> 278, 0 "Creating container" lines, 29s. Still Docker-free.
- 2026-09-22: `scripts/smoke-test.sh` -> 252 passed (was 234), 0 failed, 0 skipped.
- 2026-09-22: FAILURE SCENARIO found a real bug. Kafka stopped -> checkout returned 201 after
  **97.77s**, because KafkaTemplate.send blocks for max.block.ms (60s default) waiting for
  metadata and the AFTER_COMMIT listener runs on the request thread. Fixed with @Async onto a
  bounded pool + max.block.ms=5000: the same test now gives 0.18s and 0.05s. On recovery the
  next order is notified, though the first one lags tens of seconds while clients reconnect.
- 2026-09-22: the outage cost 4 orders their notification, permanently - the dual-write gap
  Phase 18 closes. Measured, not assumed.

## Open issues / blockers
- Carried from Phases 15-16: the Grafana dashboards' RENDER has still never been looked at by
  human eyes (all data behind them is verified). Chrome's site permissions block localhost:3000
  for browser automation here. Steps: `docs/test-reports/phase-15.md` §8 and the "EcomDemo Logs"
  dashboard with a correlation ID pasted into its textbox.

## Decisions this phase (copied to docs/decisions.md ✅ — 13 entries)
- KRaft, one broker, every replication factor written out as 1 (the internal topics default to 3
  and fail when first needed).
- Two listeners: INTERNAL kafka:9092 and HOST localhost:29092 - a client reconnects to the
  ADVERTISED address, so one address cannot serve both callers.
- Topics from NewTopic beans with auto.create.topics.enable=false.
- A hand-written event record, not the entity; keyed by order id so one order's events share a
  partition.
- AFTER_COMMIT publication, with the dual-write gap documented and measured rather than hidden.
- @Async on a bounded pool with an ABORT policy, plus max.block.ms=5000 (found by the failure
  test - see above).
- Idempotency is the consumer's job: processed_event's PRIMARY KEY is the event id from the
  message; marker and work in one transaction, marker first.
- Retries on separate TOPICS, not in the consumer thread (head-of-line blocking).
- Retry topics suffixed by INDEX, not delay (jitter would create new topics every restart).
- ErrorHandlingDeserializer, or a malformed message stops the partition for ever.
- Nothing consumes the DLT.
- A real broker in Testcontainers; Kafka switched off entirely in the fast suite.
- spring-boot-starter-kafka, not the bare spring-kafka (Boot 4 auto-config modules).

## Environment left behind
Docker Desktop RUNNING. NINE containers up (`ecomdemo-app`, `-db`, `-cache`, `-prometheus`,
`-grafana`, `-loki`, `-alloy`, `-kafka`, `-kafka-ui`), schema **v9**, image built from this
branch. The SonarQube stack was STOPPED during this phase to free memory for Testcontainers;
`docker compose -f compose.sonar.yaml up -d` brings it back. `.env` holds a real JWT_SECRET and
is gitignored.

This session cleared three abandoned Testcontainers containers, and STOPPED (not removed) a stray
`ecomdemo-postgres` from 2026-09-21 that was holding port 5432 and blocking `docker compose up`.
It is not part of `compose.yaml`; its data is intact and `docker start ecomdemo-postgres` restores
it, though nothing in this project wants it. An untracked `handoff.md` (a pasted Phase 15 session
transcript) sits in the working tree, unstaged and unused.

## Next action
STOPPED at a post-PR stop point. Phase 17 itself is merged, verified and tagged; what is open is
the ONE-FILE follow-up that merge verification earned.
https://github.com/mr-sujay-patil/ecomdemo/pull/22
- If they say `approved, merge it` -> `gh pr merge 22 --merge`, then verify, then start Phase 18
  (`docs/phases/phase-18-outbox.md`), which exists to close the dual-write gap Phase 17 measured.
  No new tag: a follow-up is not a phase, and `phase-17-complete` already points at 68769a6.
- If they say `changes: <feedback>` -> back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say so explicitly.
- Two things are outstanding for the user's eyes: the Grafana dashboards' RENDER (Phases 15-16)
  and Kafka UI at http://localhost:8090, which is the nicest way to see a partition and a key.
- The SonarQube stack is STOPPED; `docker compose -f compose.sonar.yaml up -d` brings it back.

Five traps hit and fixed, all in the test report:
1. `spring-kafka` alone gives no auto-configuration in Boot 4 - the auto-config lives in
   `spring-boot-kafka`, so the dependency must be `spring-boot-starter-kafka`. The symptom is a
   missing KafkaTemplate BEAN, which reads like an application bug.
2. V9 first used PostgreSQL spellings (BIGSERIAL, TIMESTAMPTZ, now()) and failed the whole
   Surefire suite on H2. Migrations must use the portable spelling every earlier one uses.
3. `spring.kafka.admin.auto-create=false` in the test profile: KafkaAdmin applied the NewTopic
   beans at startup and every @SpringBootTest spent 45s timing out against a broker that is not
   there.
4. Retry topics are named after the DELAY by default, and `@BackOff(jitter=...)` makes that a
   different number every run - `orders.placed-retry-1031`, `-retry-1661`, new ones for ever.
   Fixed with `TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE`.
5. The 97-second checkout (see Last test run). The fix is @Async + max.block.ms.

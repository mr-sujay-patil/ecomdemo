# Phase 18: Reliable Event Publishing

| | |
|---|---|
| **Stage** | Stage 6: Event-Driven Architecture |
| **Technology** | Transactional Outbox |
| **Branch** | `feature/phase-18-outbox` |
| **PR title** | `Phase 18: Reliable Event Publishing` |
| **Requires** | `phase-17-complete` tag exists on `main` |
| **Completion tag** | `phase-18-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Transactional Outbox pattern

**Goal:** Solve the dual-write problem between the database and Kafka.

**What you'll implement**
- An `outbox_events` table.
- Checkout saves the order and the outbox row in one transaction.
- A scheduled relay publishes pending rows and marks them as sent.
- A demo with Kafka down.
- A cleanup job for old rows.

**Concepts to understand**
- The dual-write problem
- Outbox vs CDC (Debezium)
- Ordering and duplicate trade-offs

**Done when**
- No events are lost while Kafka is down.

## Smoke test additions (`scripts/smoke-test.sh`)

Stop Kafka, place an order (it succeeds), start Kafka, and confirm the notification appears within a timeout.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

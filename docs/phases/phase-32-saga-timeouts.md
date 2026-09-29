# Phase 32: Saga Timeouts and Reconciliation

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | Scheduled reconciliation + Kafka dead-letter handling |
| **Branch** | `feature/phase-32-saga-timeouts` |
| **PR title** | `Phase 32: Saga Timeouts` |
| **Requires** | Phase 31 merged into `main` (the user allowed starting before the `phase-31-complete` tag; see `docs/progress/CURRENT.md`) |
| **Completion tag** | `phase-32-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Scheduled reconciliation + Kafka dead-letter handling

**Goal:** No order stays PENDING for ever.

**What you'll implement**
- A saga deadline: an order still PENDING after a configurable time is resolved, not left waiting.
- Reconciliation: before cancelling, ask the owners (inventory, payment) what actually happened, so a slow but successful saga is confirmed rather than cancelled, and a cancellation releases any stock that was reserved.
- A way to see and replay dead-lettered saga events (`*-dlt`), with an audit trail.
- Metrics and an alert for stuck and timed-out orders.

**Concepts to understand**
- Timeouts in choreographed sagas (who owns the clock)
- Reconciliation vs compensation
- Idempotent replay of dead-lettered events
- The "unknown outcome" problem in distributed systems

**Done when**
- An order whose saga event is dead-lettered ends CONFIRMED or CANCELLED (with stock released) within the deadline, proven by an automated test and a scripted failure scenario.

## Smoke test additions (`scripts/smoke-test.sh`)

- A saga whose event is forced to the dead-letter topic is resolved by reconciliation within the deadline.
- The stuck-order metric is exposed.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

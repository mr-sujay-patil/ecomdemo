# Phase 24: Distributed Transactions

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | Saga pattern |
| **Branch** | `feature/phase-24-saga` |
| **PR title** | `Phase 24: Distributed Transactions` |
| **Requires** | `phase-23-complete` tag exists on `main` |
| **Completion tag** | `phase-24-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Saga pattern (choreography over Kafka)

**Goal:** Keep data consistent across services without 2PC.

**What you'll implement**
- A new mock `payment-service`.
- The saga: `OrderCreated` (PENDING) → `StockReserved` / `StockRejected` → `PaymentCompleted` / `PaymentFailed` → `CONFIRMED` / `CANCELLED`.
- Compensation that releases stock when payment fails.
- Outbox publishers and idempotent consumers throughout.
- An order status endpoint.
- The orchestration alternative documented.

**Concepts to understand**
- Why 2PC is avoided
- Choreography vs orchestration
- Compensating transactions
- Eventual consistency
- Semantic locks

**Done when**
- Success and failure scenarios both end consistently, verified by an end-to-end test.

## Smoke test additions (`scripts/smoke-test.sh`)

A normal order ends CONFIRMED. A forced payment failure ends CANCELLED, and the stock is restored.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

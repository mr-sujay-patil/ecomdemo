# Phase 6: Transactions & Concurrency

| | |
|---|---|
| **Stage** | Stage 2: Data Layer |
| **Technology** | @Transactional + optimistic locking |
| **Branch** | `feature/phase-06-transactions` |
| **PR title** | `Phase 06: Transactions & Concurrency` |
| **Requires** | `phase-05-complete` tag exists on `main` |
| **Completion tag** | `phase-06-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Spring transaction management (`@Transactional`) + JPA optimistic locking

**Goal:** Guarantee correctness when things fail or happen at the same time.

**What you'll implement**
- An atomic place-order: any stock failure rolls back everything.
- `@Transactional(readOnly = true)` on read services.
- `@Version` on `Product`. Handle `OptimisticLockingFailureException` with a limited retry and a 409 response.
- An `order_audit` table written with `Propagation.REQUIRES_NEW`.
- A concurrency test: two threads buy the last unit, and exactly one succeeds.

**Concepts to understand**
- ACID
- Propagation types
- Isolation levels and the anomalies each prevents
- Rollback rules
- The self-invocation proxy pitfall
- Optimistic vs pessimistic locking

**Done when**
- The concurrency test passes reliably, and failed orders leave no partial data.

## Smoke test additions (`scripts/smoke-test.sh`)

Set a product's stock to 1, fire two parallel orders for it, and expect exactly one success and one 409. Stock ends at 0.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

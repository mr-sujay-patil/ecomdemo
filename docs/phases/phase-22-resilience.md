# Phase 22: Resilience

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | Resilience4j |
| **Branch** | `feature/phase-22-resilience` |
| **PR title** | `Phase 22: Resilience` |
| **Requires** | `phase-21-complete` tag exists on `main` |
| **Completion tag** | `phase-22-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Resilience4j

**Goal:** Stay functional when dependencies fail.

**What you'll implement**
- Circuit breaker, retry, and timeout on order → catalog calls, with fallbacks (a clear 503).
- A bulkhead.
- Resilience metrics in Grafana.
- A failure demo.

**Concepts to understand**
- Cascading failures
- Circuit breaker states
- Backoff with jitter
- Retry storms
- Timeouts
- Bulkheads

**Done when**
- Stopping a downstream service degrades the system gracefully.

## Smoke test additions (`scripts/smoke-test.sh`)

Stop catalog-service: checkout fails fast (under 2 s) with 503. Restart it: the flow recovers.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

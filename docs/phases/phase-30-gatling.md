# Phase 30: Performance Testing

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | Gatling |
| **Branch** | `feature/phase-30-gatling` |
| **PR title** | `Phase 30: Performance Testing` |
| **Requires** | `phase-29-complete` tag exists on `main` |
| **Completion tag** | `phase-30-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Gatling (Java DSL)

**Goal:** Understand system behaviour under load.

**What you'll implement**
- Browse, checkout, and mixed simulations.
- Ramp, steady, and spike load profiles.
- Comparisons with and without the cache and with different pool sizes.
- Findings in `docs/performance.md`.

**Concepts to understand**
- Throughput and latency percentiles
- Load, stress, spike, and soak tests
- Bottleneck analysis
- Virtual threads

**Done when**
- At least one bottleneck is found, fixed, and documented.

## Smoke test additions (`scripts/smoke-test.sh`)

No new checks. Gatling results are added to the test report.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

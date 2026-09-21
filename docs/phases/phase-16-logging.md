# Phase 16: Centralized Logging

| | |
|---|---|
| **Stage** | Stage 5: Performance & Background Work |
| **Technology** | Grafana Loki |
| **Branch** | `feature/phase-16-logging` |
| **PR title** | `Phase 16: Centralized Logging` |
| **Requires** | `phase-15-complete` tag exists on `main` |
| **Completion tag** | `phase-16-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Grafana Loki (with structured JSON logging)

**Goal:** Search and correlate logs in one place.

**What you'll implement**
- Structured JSON console logging.
- A correlation ID filter with MDC, also returned in a response header.
- Loki and Grafana Alloy in Compose, with Loki as a Grafana data source.
- No sensitive data in logs.

**Concepts to understand**
- Structured logging
- MDC
- Log levels
- Aggregation architecture (vs ELK)

**Done when**
- All logs for one request can be found in Grafana by correlation ID.

## Smoke test additions (`scripts/smoke-test.sh`)

Every response carries an `X-Correlation-Id` header. Querying Loki's API for that ID returns log lines.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

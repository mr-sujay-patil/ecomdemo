# Phase 15: Metrics & Monitoring

| | |
|---|---|
| **Stage** | Stage 5: Performance & Background Work |
| **Technology** | Actuator + Prometheus + Grafana |
| **Branch** | `feature/phase-15-metrics` |
| **PR title** | `Phase 15: Metrics & Monitoring` |
| **Requires** | `phase-14-complete` tag exists on `main` |
| **Completion tag** | `phase-15-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Actuator + Micrometer + Prometheus + Grafana

**Goal:** See what the app is doing in real time.

**What you'll implement**
- Exposed endpoints: health (with liveness and readiness groups), info, metrics, and prometheus.
- Business metrics: `orders.placed`, `order.value`, and a checkout timer.
- Prometheus and Grafana in Compose, with a provisioned dashboard and one alert rule.

**Concepts to understand**
- Counter, gauge, timer, and distribution summary
- The pull model
- The RED and USE methods
- Liveness vs readiness

**Done when**
- The dashboard shows live traffic and business metrics.

## Smoke test additions (`scripts/smoke-test.sh`)

After placing an order, `/actuator/prometheus` shows `orders_placed_total` incremented. Liveness and readiness are UP.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

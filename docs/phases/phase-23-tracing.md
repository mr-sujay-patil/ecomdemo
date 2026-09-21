# Phase 23: Distributed Tracing

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | OpenTelemetry + Tempo |
| **Branch** | `feature/phase-23-tracing` |
| **PR title** | `Phase 23: Distributed Tracing` |
| **Requires** | `phase-22-complete` tag exists on `main` |
| **Completion tag** | `phase-23-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Micrometer Tracing + OpenTelemetry (Grafana Tempo)

**Goal:** Follow one request across services.

**What you'll implement**
- Tracing in all services, with propagation over HTTP and Kafka.
- Tempo in Compose.
- Trace IDs in logs, linked from Loki to Tempo.
- A sampling configuration.

**Concepts to understand**
- Traces, spans, and W3C Trace Context
- Sampling
- The three pillars of observability

**Done when**
- One checkout shows as a single end-to-end trace.

## Smoke test additions (`scripts/smoke-test.sh`)

A trace ID from one checkout is found in Tempo's API with spans from at least 4 services.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

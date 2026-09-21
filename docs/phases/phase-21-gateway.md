# Phase 21: API Gateway

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | Spring Cloud Gateway |
| **Branch** | `feature/phase-21-gateway` |
| **PR title** | `Phase 21: API Gateway` |
| **Requires** | `phase-20-complete` tag exists on `main` |
| **Completion tag** | `phase-21-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Spring Cloud Gateway

**Goal:** A single entry point for clients.

**What you'll implement**
- A `gateway-service` with routes to all services.
- JWT validation at the edge.
- A Redis-backed rate limiter.
- CORS configuration.
- A correlation ID filter.
- A Spring Cloud release train matching your Boot version.

**Concepts to understand**
- Gateway responsibilities
- Filters and predicates
- Gateway vs load balancer vs service mesh

**Done when**
- Clients only use the gateway, and rate limits return 429.

## Smoke test additions (`scripts/smoke-test.sh`)

All calls go through the gateway port only. A request burst returns 429.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

# Phase 20: Microservices Split

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | Multi-service architecture |
| **Branch** | `feature/phase-20-microservices` |
| **PR title** | `Phase 20: Microservices Split` |
| **Requires** | `phase-19-complete` tag exists on `main` |
| **Completion tag** | `phase-20-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Multi-service architecture (Maven multi-module, database per service)

**Goal:** Make the modules independently deployable services.

**What you'll implement**
- Services: `catalog-service`, `inventory-service`, `order-service` (cart + orders + outbox + reports), `customer-service` (users + JWT), and `notification-service`.
- A database per service, each with its own Flyway migrations.
- Synchronous calls through `RestClient` / HTTP Interface clients; asynchronous communication through Kafka.
- JWT validation in each service.
- All services in Compose.

**Concepts to understand**
- Database-per-service
- Synchronous vs asynchronous trade-offs
- Eventual consistency
- Service contracts and versioning

**Done when**
- The full purchase flow works across services.

## Smoke test additions (`scripts/smoke-test.sh`)

The script runs against the individual services' ports, and the full flow still works end to end.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

# Phase 17: Messaging

| | |
|---|---|
| **Stage** | Stage 6: Event-Driven Architecture |
| **Technology** | Apache Kafka |
| **Branch** | `feature/phase-17-kafka` |
| **PR title** | `Phase 17: Messaging` |
| **Requires** | `phase-16-complete` tag exists on `main` |
| **Completion tag** | `phase-17-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Apache Kafka (KRaft mode)

**Goal:** Learn asynchronous, event-driven communication, still inside the monolith.

**What you'll implement**
- Kafka (single broker, KRaft) and Kafka UI in Compose.
- An `OrderPlacedEvent` published to `orders.placed`, keyed by order id.
- A `notification` consumer that writes a log line and a `notifications` row.
- Retry topics with backoff and a dead-letter topic.
- An idempotent consumer backed by a `processed_events` table.
- A Testcontainers Kafka test.

**Concepts to understand**
- Topics, partitions, offsets, and replication
- Consumer groups
- Keys and ordering
- Delivery semantics
- Idempotency
- Dead-letter topics

**Done when**
- Each order produces exactly one notification, and poison messages land in the DLT.

## Smoke test additions (`scripts/smoke-test.sh`)

After placing an order, exactly one notification row exists for it, and the event is on the `orders.placed` topic.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

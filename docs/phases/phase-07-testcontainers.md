# Phase 7: Integration Testing

| | |
|---|---|
| **Stage** | Stage 2: Data Layer |
| **Technology** | Testcontainers |
| **Branch** | `feature/phase-07-testcontainers` |
| **PR title** | `Phase 07: Integration Testing` |
| **Requires** | `phase-06-complete` tag exists on `main` |
| **Completion tag** | `phase-07-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Testcontainers

**Goal:** Test against real PostgreSQL.

**What you'll implement**
- The Testcontainers PostgreSQL module with `@ServiceConnection`.
- One HTTP-level integration test per feature.
- A shared, reusable container configuration.
- Surefire for unit tests and Failsafe for `*IT.java` tests.

**Concepts to understand**
- "Works on H2" ≠ "works on PostgreSQL"
- Container lifecycle and reuse
- Surefire vs Failsafe

**Done when**
- `./mvnw verify` runs both unit and integration tests.

## Smoke test additions (`scripts/smoke-test.sh`)

No new checks. The existing script must still pass.

## Your manual steps (user)

Keep Docker Desktop running from this phase onwards.

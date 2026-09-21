# Phase 19: Modular Monolith

| | |
|---|---|
| **Stage** | Stage 7: Distributed System |
| **Technology** | Spring Modulith |
| **Branch** | `feature/phase-19-modulith` |
| **PR title** | `Phase 19: Modular Monolith` |
| **Requires** | `phase-18-complete` tag exists on `main` |
| **Completion tag** | `phase-19-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Spring Modulith

**Goal:** Enforce module boundaries before splitting into services.

**What you'll implement**
- Modules: `catalog`, `inventory`, `customer`, `cart`, `order`, `notification`, and `shared`, each with a public API and an `internal` package.
- Events replace direct cross-module calls where appropriate.
- An `ApplicationModules.verify()` test and generated documentation.

**Concepts to understand**
- Bounded contexts
- Coupling and cohesion
- Why not "microservices first"
- Synchronous calls vs events

**Done when**
- The verification test passes, and the diagrams show clean dependencies.

## Smoke test additions (`scripts/smoke-test.sh`)

No new checks. The existing script must still pass unchanged.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

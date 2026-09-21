# Phase 4: PostgreSQL

| | |
|---|---|
| **Stage** | Stage 2: Data Layer |
| **Technology** | PostgreSQL |
| **Branch** | `feature/phase-04-postgresql` |
| **PR title** | `Phase 04: PostgreSQL` |
| **Requires** | `phase-03-complete` tag exists on `main` |
| **Completion tag** | `phase-04-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** PostgreSQL

**Goal:** Move from an in-memory database to a real relational database.

**What you'll implement**
- Replace H2 with the PostgreSQL driver. Run PostgreSQL through a native installation or a single `docker run` command documented in the README.
- Profiles: `application-dev.properties` and `application-test.properties`.
- Credentials from environment variables, with local defaults in the dev profile.
- Keep `ddl-auto=update` for now.

**Concepts to understand**
- Spring profiles and externalized configuration
- HikariCP connection pooling
- H2 vs PostgreSQL differences
- Inspecting the schema with DBeaver or pgAdmin

**Done when**
- Data survives restarts, and all tests pass.

## Smoke test additions (`scripts/smoke-test.sh`)

Create a product, restart the application, and confirm the product still exists.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

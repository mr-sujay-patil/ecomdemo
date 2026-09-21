# Phase 5: Database Migrations

| | |
|---|---|
| **Stage** | Stage 2: Data Layer |
| **Technology** | Flyway |
| **Branch** | `feature/phase-05-flyway` |
| **PR title** | `Phase 05: Database Migrations` |
| **Requires** | `phase-04-complete` tag exists on `main` |
| **Completion tag** | `phase-05-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Flyway

**Goal:** Version-control the database schema.

**What you'll implement**
- `V1__init_schema.sql` and `V2__seed_products.sql` (replacing `data.sql`).
- `spring.jpa.hibernate.ddl-auto=validate`.
- `V3__add_product_category.sql` (a new column plus an index) to practice schema evolution.

**Concepts to understand**
- Why `ddl-auto=update` is dangerous in production
- Versioned vs repeatable migrations
- `flyway_schema_history` and checksums
- Never editing an applied migration
- Backward-compatible changes

**Done when**
- A fresh database is built entirely by Flyway, and Hibernate validation passes.

## Smoke test additions (`scripts/smoke-test.sh`)

`flyway_schema_history` shows V1–V3 as successful. Product responses include `category`.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

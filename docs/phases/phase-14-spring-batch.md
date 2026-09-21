# Phase 14: Batch Processing

| | |
|---|---|
| **Stage** | Stage 5: Performance & Background Work |
| **Technology** | Spring Batch |
| **Branch** | `feature/phase-14-spring-batch` |
| **PR title** | `Phase 14: Batch Processing` |
| **Requires** | `phase-13-complete` tag exists on `main` |
| **Completion tag** | `phase-14-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Spring Batch

**Goal:** Process large data volumes reliably.

**What you'll implement**
- **Job 1, product CSV import:** chunk-oriented processing, validation, upsert, a skip limit, and an error file.
- **Job 2, daily sales report:** a CSV of order count, revenue, and top products.
- The JobRepository schema in PostgreSQL (through Flyway).
- Triggers: an ADMIN upload endpoint (Job 1) and `@Scheduled` cron (Job 2).
- A restartability demo.

**Concepts to understand**
- Job, Step, Reader, Processor, and Writer
- Chunk vs tasklet steps
- JobInstance vs JobExecution
- Skip, retry, and restart
- Transaction boundaries per chunk

**Done when**
- A 10,000-row import works with invalid rows skipped, and the report is generated on schedule.

## Smoke test additions (`scripts/smoke-test.sh`)

Upload a sample CSV with some invalid rows: the job ends COMPLETED, the product count increases, and the skip count matches the invalid rows.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

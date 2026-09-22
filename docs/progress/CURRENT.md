# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 14: Batch Processing
- **Branch:** feature/phase-14-spring-batch
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 13 merge verification (passed 2026-09-22)
PR #15 MERGED with a merge commit (d674e1c, 2 parents: 1b03f5f + 8ecee91); branch is an ancestor
of `main`; no commits and no file diffs between branch and `main`; all 14 feature branches intact
locally and on GitHub; `cache/CacheConfig`, `cache/CacheNames`, `cache/LoggingCache`,
`cache/CacheApiIT`, `support/RedisContainerConfig` and the compose `cache` service all present in
`main`. **CI on `main` green** (run 35707090615, both jobs) and the image published to GHCR.
`./mvnw clean verify` on `main` -> BUILD SUCCESS, 190 unit + 38 IT, 0 failures, 0 skipped.
`scripts/smoke-test.sh` against the compose stack -> 136 passed, 0 failed, 0 skipped.
Tag `phase-13-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] Job 1, product CSV import: chunk-oriented, validation, upsert, skip limit, error file
- [x] Job 2, daily sales report: CSV of order count, revenue and top products
- [x] The JobRepository schema in PostgreSQL, through Flyway (V7) + V8 index on product.name
- [x] Triggers: an ADMIN upload endpoint (Job 1) and `@Scheduled` cron (Job 2)
- [x] A restartability demo (ProductImportJobIT + README "try it yourself")
- [x] Done when: a 10,000-row import works with invalid rows skipped, and the report is
      generated on schedule
- [x] Smoke test additions (upload a CSV with invalid rows: job COMPLETED, product count up,
      skip count matches the invalid rows) — 20 new checks
- [x] Testing protocol run in full + docs/test-reports/phase-14.md
- [x] README section, decisions.md (16 entries), RECENT.md rotation (Phase 12 archived),
      tracker -> 🔵
- [ ] PR raised, CI green

## Last test run
- 2026-09-22: `./mvnw verify` -> BUILD SUCCESS, Surefire 229 (was 190) + Failsafe 47 (was 38),
  0 failures, 0 skipped. One postgres and one redis container for the whole Failsafe run.
- 2026-09-22: `./mvnw clean test` -> 0 "Creating container" lines; the Docker-free fast suite
  still holds.
- 2026-09-22: `scripts/smoke-test.sh` against the compose stack -> 156 passed (was 136), 0 failed,
  **0 skipped**.
- 2026-09-22: restart demo run by hand against the compose stack: execution 5 FAILED (100 written,
  50 skipped, cause chain naming the skip limit), the staged file fixed in place with `sed`,
  execution 6 of instance 5 COMPLETED reading only 80 of 180 rows. Demo products cleaned up.

## Open issues / blockers
- none. The big one is fixed: Spring Batch 6 defaults to `ResourcelessJobRepository` (in memory),
  so every job ran fine and NOTHING was written to the BATCH_ tables. `BatchConfig` now extends
  `DefaultBatchConfiguration` and supplies a `JdbcJobRepositoryFactoryBean`;
  `BatchJobRepositoryTest` asserts rows in the tables so it cannot regress silently.

## Decisions this phase (copied to docs/decisions.md ❌ — not yet)
- The JobRepository is JDBC-backed by an explicit `BatchConfig extends DefaultBatchConfiguration`.
- V7 is Spring Batch's own schema-postgresql.sql, verbatim; V8 indexes product.name for the
  upsert lookup (NOT unique - the API has always allowed duplicate names).
- Upload is staged to disk under a UUID name: restart re-reads the same path, and a unique path
  makes each upload its own JobInstance.
- Only two exception types are skippable; the skip limit failure is what the restart demo uses.
- The import evicts the Phase 13 caches in afterStep, never inside the chunk transaction.
- The import runs synchronously (SyncTaskExecutor), so the response carries the real counters.
- A restart resumes past SKIPPED rows: they are recovered by re-importing, not by restarting.

## Environment left behind
Docker Desktop RUNNING. Application stack up and healthy (`ecomdemo-app`, `ecomdemo-db`,
`ecomdemo-cache`), schema v6. SonarQube stack (`ecomdemo-sonarqube`, `ecomdemo-sonar-db`) also up
at http://localhost:9000; stop it with `docker compose -f compose.sonar.yaml down` if the memory
is wanted back. `.env` holds a real JWT_SECRET and is gitignored. No stray Java processes.

## Next action
Everything except the PR is done and committed. Next: push the branch, `gh pr create --base main`
with the template filled in, wait for CI, then send the Phase Review Report and **STOP** at the
mandatory post-PR stop point.
- If they say `merged, continue` -> merge verification (execution-protocol §5) on `main`, then
  tag `phase-14-complete` and start Phase 15 (`docs/phases/phase-15-metrics.md`).
- If they say `changes: <feedback>` -> back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say exactly `approved, merge it`.

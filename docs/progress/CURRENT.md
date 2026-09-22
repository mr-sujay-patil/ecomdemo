# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 14: Batch Processing
- **Branch:** feature/phase-14-spring-batch
- **Step:** BRANCHED
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
- [ ] Job 1, product CSV import: chunk-oriented, validation, upsert, skip limit, error file
- [ ] Job 2, daily sales report: CSV of order count, revenue and top products
- [ ] The JobRepository schema in PostgreSQL, through Flyway (V7)
- [ ] Triggers: an ADMIN upload endpoint (Job 1) and `@Scheduled` cron (Job 2)
- [ ] A restartability demo
- [ ] Done when: a 10,000-row import works with invalid rows skipped, and the report is
      generated on schedule
- [ ] Smoke test additions (upload a CSV with invalid rows: job COMPLETED, product count up,
      skip count matches the invalid rows)
- [ ] Testing protocol run in full + docs/test-reports/phase-14.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 12 archived), tracker -> 🔵
- [ ] PR raised, CI green

## Last test run
- 2026-09-22 (on `main`, phase 13 merge verification): `./mvnw clean verify` -> 190 + 38,
  0 failures, 0 skipped. `scripts/smoke-test.sh` -> 136 passed, 0 failed, 0 skipped.

## Open issues / blockers
- none yet.

## Decisions this phase (copied to docs/decisions.md ❌ — not yet)
- none yet.

## Environment left behind
Docker Desktop RUNNING. Application stack up and healthy (`ecomdemo-app`, `ecomdemo-db`,
`ecomdemo-cache`), schema v6. SonarQube stack (`ecomdemo-sonarqube`, `ecomdemo-sonar-db`) also up
at http://localhost:9000; stop it with `docker compose -f compose.sonar.yaml down` if the memory
is wanted back. `.env` holds a real JWT_SECRET and is gitignored. No stray Java processes.

## Next action
Phase 14 has just been branched from `main` (housekeeping commit). Next: read
`docs/phases/phase-14-spring-batch.md` and start IMPLEMENTING, first checklist item first —
add the Spring Batch starter and the Flyway V7 migration for the JobRepository schema, then
Job 1 (CSV import), then the upload endpoint, then Job 2 and its schedule.

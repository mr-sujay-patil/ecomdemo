# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 16: Centralized Logging (Grafana Loki)
- **Branch:** feature/phase-16-logging
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 15 merge verification (passed 2026-09-22)
PR #17 MERGED with a merge commit (77a9d13, 2 parents: a5ff674 + 8345ab6); the branch is an
ancestor of `main`; no commits and no file diffs between branch and `main`; all 16 branches
intact on GitHub. The whole `com.ecomdemo.metrics` package, `docker/prometheus/*`,
`docker/grafana/*` and the Phase 15 test classes are present in `main`.
**CI on `main` green** (run 35716925251: `Build and test` SUCCESS, `Publish image to GHCR` SUCCESS).
`./mvnw clean verify` on `main` -> BUILD SUCCESS, 241 unit + 62 IT, 0 failures, 0 skipped, 1m12s.
`docker compose up -d --build` from `main` recreated `ecomdemo-app`; `scripts/smoke-test.sh`
-> 204 passed, 0 failed, 0 skipped. Tag `phase-15-complete` pushed.
**Environment gotcha found during verification:** `git checkout main` deleted `docker/grafana/`
and `docker/prometheus/` (they did not exist in `main` before the merge) and the pull recreated
them with new inodes, so the already-running Grafana container's bind mounts pointed at the stale
directories and saw them EMPTY - the 2 dashboard smoke checks failed. Fix:
`docker compose up -d --force-recreate grafana prometheus`. Not a code defect; re-run was clean.

## Checklist (copied from the phase's "What you'll implement")
- [ ] Structured JSON console logging
- [ ] A correlation ID filter with MDC, also returned in a response header
- [ ] Loki and Grafana Alloy in Compose, with Loki as a Grafana data source
- [ ] No sensitive data in logs
- [ ] Done when: all logs for one request can be found in Grafana by correlation ID
- [ ] Smoke test additions: every response carries an `X-Correlation-Id` header; querying Loki's
      API for that ID returns log lines
- [ ] Testing protocol run in full + docs/test-reports/phase-16.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 14 archived),
      tracker -> 🔵
- [ ] PR raised

## Last test run
- 2026-09-22 (merge verification on `main`): `./mvnw clean verify` -> 241 + 62, 0 failures,
  0 skipped. `scripts/smoke-test.sh` -> 204 passed, 0 failed, 0 skipped.

## Open issues / blockers
- Carried over from Phase 15: the Grafana dashboard's RENDER has still never been looked at by
  human eyes (its data is fully verified through the Prometheus API). Chrome's site permissions
  block localhost:3000 for browser automation here. Steps: `docs/test-reports/phase-15.md` §8.

## Decisions this phase (copy to docs/decisions.md before the PR)
- (none yet)

## Environment left behind
Docker Desktop RUNNING. Application stack up and healthy (`ecomdemo-app`, `ecomdemo-db`,
`ecomdemo-cache`, `ecomdemo-prometheus`, `ecomdemo-grafana`), schema v8, image built from `main`.
SonarQube stack (`ecomdemo-sonarqube`, `ecomdemo-sonar-db`) also up at http://localhost:9000;
stop it with `docker compose -f compose.sonar.yaml down` if the memory is wanted back. `.env`
holds a real JWT_SECRET and is gitignored. No stray Java processes.

## Next action
Start IMPLEMENTING Phase 16 on `feature/phase-16-logging`, first checklist item first:
structured JSON console logging. Read `docs/phases/phase-16-logging.md` for the scope and
`docs/process/testing-protocol.md` before the PR. Nothing is waiting on the user.

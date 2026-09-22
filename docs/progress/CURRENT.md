# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 15: Metrics & Monitoring
- **Branch:** feature/phase-15-metrics
- **Step:** IMPLEMENTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Phase 14 merge verification (passed 2026-09-22)
PR #16 MERGED with a merge commit (a5ff674, 2 parents: d674e1c + e11d777); the branch is an
ancestor of `main`; no commits and no file diffs between branch and `main`; all 15 branches intact
on GitHub. The whole `com.ecomdemo.batch` package, `V7__batch_job_repository.sql`,
`V8__index_product_name.sql` and the 7 batch test classes are present in `main`.
**CI on `main` green** (run 35711849347: `Build and test` SUCCESS, `Publish image to GHCR` SUCCESS).
`./mvnw clean verify` on `main` -> BUILD SUCCESS, 229 unit + 47 IT, 0 failures, 0 skipped.
`docker compose up -d --build` from `main` rebuilt `ecomdemo:latest` (identical digest, so no
container recreate); `scripts/smoke-test.sh` -> 156 passed, 0 failed, 0 skipped.
Tag `phase-14-complete` pushed.

## Checklist (copied from the phase's "What you'll implement")
- [x] Exposed endpoints: health (with liveness and readiness groups), info, metrics, prometheus
- [x] Business metrics: `orders.placed`, `order.value`, and a checkout timer
- [ ] Prometheus and Grafana in Compose, with a provisioned dashboard and one alert rule
- [ ] Done when: the dashboard shows live traffic and business metrics
- [ ] Smoke test additions: after placing an order, `/actuator/prometheus` shows
      `orders_placed_total` incremented; liveness and readiness are UP
- [ ] Testing protocol run in full + docs/test-reports/phase-15.md
- [ ] README section, docs/decisions.md entries, RECENT.md rotation (Phase 13 archived),
      tracker -> 🔵
- [ ] PR raised, CI green

## Last test run
- 2026-09-22: `./mvnw test` -> 236 unit tests (was 229), 0 failures, 0 skipped.
- 2026-09-22 (on `main`, phase 14 verification): `./mvnw clean verify` -> BUILD SUCCESS,
  229 + 47, 0 failures, 0 skipped. `scripts/smoke-test.sh` -> 156 passed, 0 failed, 0 skipped.
- Nothing run yet for phase 15.

## Open issues / blockers
- none.

## Decisions this phase (copied to docs/decisions.md ❌ — not yet)
- none yet.

## Environment left behind
Docker Desktop RUNNING. Application stack up and healthy (`ecomdemo-app`, `ecomdemo-db`,
`ecomdemo-cache`), schema v8, image built from `main`. SonarQube stack (`ecomdemo-sonarqube`,
`ecomdemo-sonar-db`) also up at http://localhost:9000; stop it with
`docker compose -f compose.sonar.yaml down` if the memory is wanted back. `.env` holds a real
JWT_SECRET and is gitignored. No stray Java processes.

## Next action
Actuator + the three business meters are committed (af24a9d). Next: the Prometheus and Grafana
compose services with a provisioned datasource, dashboard and one alert rule, then the
Dockerfile healthcheck moved onto /actuator/health/readiness, then the smoke test additions.
Phase file: `docs/phases/phase-15-metrics.md`.

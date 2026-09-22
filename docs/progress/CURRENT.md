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
- [x] Prometheus and Grafana in Compose, with a provisioned dashboard and one alert rule
- [x] Done when: the dashboard shows live traffic and business metrics (data verified;
      the visual render is ⚠️ manual - see below)
- [x] Smoke test additions: after placing an order, `/actuator/prometheus` shows
      `orders_placed_total` incremented; liveness and readiness are UP - 48 new checks
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
- ⚠️ The Grafana dashboard could not be screenshotted: Chrome's site permissions block
  localhost:3000 for browser automation in this environment. Verified instead by running all 16
  of the dashboard's own panel queries through the Prometheus API - every one returns live data.
  The visual render needs the user's eyes; steps go in the test report.

## Decisions this phase (copied to docs/decisions.md ❌ — not yet)
- Actuator stays on the main port 8080; the production answer (management.server.port on an
  internal-only network) is named in SecurityConfig and deliberately out of scope.
- health/info/prometheus are anonymous because their callers are machines with no credentials;
  everything else Actuator exposes is ADMIN via EndpointRequest.toAnyEndpoint().
- Exposure is an explicit allow-list, so /actuator/env is a 404 even for an ADMIN.
- Readiness = readinessState + db. Redis is deliberately excluded: a cache outage is a
  slowdown the app survives, and including it would turn that into an outage.
- Liveness = livenessState alone. No dependency belongs in the probe whose remedy is a restart.
- The container healthcheck moved from /api/products to /actuator/health/readiness.
- checkout.duration wraps the whole retry loop, so one checkout is one observation.
- Five outcome tag values including a catch-all `error`, so the RED error rate cannot lie by
  omission.
- Every meter pre-registered in the CheckoutMetrics constructor: an absent series is not zero,
  and an alert on one can never fire.
- percentiles-histogram, not percentiles: quantiles are computed by Prometheus from buckets so
  they can be aggregated across instances.
- A MeterFilter drops /actuator URIs, so the scrape is not the busiest endpoint in the shop.
- One alert rule, on the conflict SHARE rather than the count, with `for: 10m`.
- Grafana datasource and dashboard are provisioned from files; UI edits are overwritten.

## Environment left behind
Docker Desktop RUNNING. Application stack up and healthy (`ecomdemo-app`, `ecomdemo-db`,
`ecomdemo-cache`), schema v8, image built from `main`. SonarQube stack (`ecomdemo-sonarqube`,
`ecomdemo-sonar-db`) also up at http://localhost:9000; stop it with
`docker compose -f compose.sonar.yaml down` if the memory is wanted back. `.env` holds a real
JWT_SECRET and is gitignored. No stray Java processes.

## Next action
Implementation is complete and committed (af24a9d, 5ac2f17, 0a1262d). Remaining: finish the
testing protocol - failure scenario (stop redis, readiness must stay UP; stop db, readiness must
go DOWN while liveness stays UP), then `docs/test-reports/phase-15.md`, README section,
decisions.md, RECENT.md rotation (Phase 13 archived), tracker -> 🔵, then raise the PR and STOP.

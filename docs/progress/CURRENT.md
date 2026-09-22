# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 16: Centralized Logging (Grafana Loki)
- **Branch:** feature/phase-16-logging
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #18 MERGED (e407627). Follow-up PR #19 raised from the same branch - see below
  https://github.com/mr-sujay-patil/ecomdemo/pull/18
- **Waiting for user:** YES - review and merge the FOLLOW-UP PR #19

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
- [x] Structured JSON console logging
- [x] A correlation ID filter with MDC, also returned in a response header
- [x] Loki and Grafana Alloy in Compose, with Loki as a Grafana data source
- [x] No sensitive data in logs
- [x] Done when: all logs for one request can be found in Grafana by correlation ID
- [x] Smoke test additions: every response carries an `X-Correlation-Id` header; querying Loki's
      API for that ID returns log lines - 23 new checks, all passing
- [x] Testing protocol run in full + docs/test-reports/phase-16.md
- [x] README section, docs/decisions.md entries (15), RECENT.md rotation (Phase 14 archived),
      tracker -> 🔵
- [x] PR raised

## Last test run
- 2026-09-22: `./mvnw clean verify` -> BUILD SUCCESS, Surefire 268 (was 241) + Failsafe 70
  (was 62), 0 failures, 0 skipped, 1m04s.
- 2026-09-22: `./mvnw clean test` -> 268, 0 "Creating container" lines; the fast suite is still
  Docker-free.
- 2026-09-22: `scripts/smoke-test.sh` -> 227 passed, 0 failed, 0 skipped on the first runs;
  now **226 passed, 1 failed** on a PRE-EXISTING bug (see below). All 23 of this phase's own
  checks pass in every run.
- 2026-09-22: drift guards verified by MUTATION - renaming the correlation field in
  config.alloy fails StructuredLoggingTest; widening the derived-field regex fails
  LoggingStackConfigTest. Both files restored and green.
- 2026-09-22: failure scenario. Loki STOPPED -> the app answered 200 with no added latency and
  the line written during the outage arrived in Loki after recovery (Alloy buffered and
  retried).
- 2026-09-22: secrecy checks proved non-vacuous - the same Loki query returns 344 lines for a
  string that IS in the logs and 0 for the password and the bearer token.

## Open issues / blockers
- ⚠️ **KNOWN DEFECT, accepted by the user (2026-09-22), carried not fixed.**
  `scripts/smoke-test.sh` fails one check, "failed checkout did not touch
  stock". It is NOT this phase's: placing an order decrements product.stock_quantity in the
  database and evicts NEITHER Phase 13 cache, so GET /api/products/{id} serves the pre-order
  stock for up to the 300s TTL. Reproduced directly (DB 2, API 4); the `order` package contains
  no eviction at all; `git diff main -- src/main/java/com/ecomdemo/{order,product,cache}` is
  empty. Fixing it is Phase 13 scope (hard rule 7) and weakening the check is forbidden (hard
  rule 8). The user chose to ACCEPT it as a known defect: the check stays exactly as it is and
  the smoke test stays red on it until a later phase picks it up. Full evidence in
  `docs/test-reports/phase-16.md` §7.
- Carried over from Phase 15: the Grafana dashboards' RENDER has still never been looked at by
  human eyes (all data behind them is verified). Chrome's site permissions block localhost:3000
  for browser automation here. Steps: `docs/test-reports/phase-15.md` §8, and for this phase the
  "EcomDemo Logs" dashboard with a correlation ID pasted into its textbox.

## Decisions this phase (copied to docs/decisions.md ✅ — 15 entries)
- LOG_FORMAT as an environment variable, not a baked-in property: the format is a deployment
  decision, and an empty value is Boot's own "not structured".
- ECS rather than logstash/gelf, and NO logback-spring.xml - structured logging is native to
  Boot since 3.4, so the whole feature is two properties and no new dependency.
- MDC key `correlation_id` (flat) vs header `X-Correlation-Id`: a dotted MDC key becomes a
  NESTED JSON object in ECS output.
- An inbound ID is validated against [A-Za-z0-9_-]{8,64} and REPLACED, never rejected: log
  injection is the threat, and a 400 would turn diagnostics into an availability problem.
- CorrelationIdFilter at HIGHEST_PRECEDENCE, ahead of Spring Security at -100, and the header is
  set BEFORE the chain runs.
- MDC cleared in a `finally` and shouldNotFilterErrorDispatch() false - a leaked ID files the
  NEXT request's lines under this one's story.
- RequestLogFilter describes a request but never quotes it: no headers, no body, no query string.
- RequestLogFilter skips /actuator; CorrelationIdFilter deliberately does not.
- correlation_id is STRUCTURED METADATA, not a Loki label: one value per request would be one
  Loki stream per request.
- The application writes to stdout and knows nothing about Loki; Alloy ships.
- Alloy uses an allow-list of services (compose.sonar.yaml shares the Compose project name).
- No HTTP health check for Loki (distroless image) or Alloy (no curl/wget); readiness is checked
  from the host in the smoke test.
- 7-day retention WITH compactor.retention_enabled - without it the period configures nothing.
- A second dashboard rather than log panels on the Phase 15 overview.
- StructuredLoggingTest asserts config.alloy's JMESPath expressions against a real ECS document.

## Environment left behind
Docker Desktop RUNNING. Application stack up and healthy (`ecomdemo-app`, `ecomdemo-db`,
`ecomdemo-cache`, `ecomdemo-prometheus`, `ecomdemo-grafana`, `ecomdemo-loki`, `ecomdemo-alloy`),
schema v8, image built from this branch.
SonarQube stack (`ecomdemo-sonarqube`, `ecomdemo-sonar-db`) also up at http://localhost:9000;
stop it with `docker compose -f compose.sonar.yaml down` if the memory is wanted back. `.env`
holds a real JWT_SECRET and is gitignored. No stray Java processes.

## Next action
STOPPED at the mandatory post-PR stop point. PR raised with the known defect named in its body.
- If they say `merged, continue` -> merge verification (execution-protocol §5) on `main`, then
  tag `phase-16-complete` and start Phase 17 (`docs/phases/phase-17-kafka.md`). NOTE: the merge
  verification's smoke run on `main` will fail the same one check - that is expected and
  accepted, and is NOT a reason to stop, but every other check must pass.
- If they say `changes: <feedback>` -> back to IMPLEMENTING on this same branch.
- Do NOT merge unless they say exactly `approved, merge it`.
- Do NOT weaken or remove the failing smoke check; it is correct and the application is wrong.
- Two things are outstanding for the user: look at the Grafana dashboards' RENDER, and decide
  when the Phase 13 stale-cache defect gets its own fix branch.

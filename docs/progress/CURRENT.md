# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-27
- **Phase:** 22: Resilience (Resilience4j)
- **Branch:** feature/phase-22-resilience
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** see `gh pr list --head feature/phase-22-resilience`
- **Waiting for user:** YES — review the PR

## Checklist (from `docs/phases/phase-22-resilience.md`) — all done
- [x] Circuit breaker, retry and timeout on order → catalog calls, with fallbacks (a clear 503)
- [x] A bulkhead (semaphore, 20, no wait; ignored by the breaker)
- [x] Resilience metrics in Grafana (`ecomdemo-resilience.json`, checked by `DashboardMetricsTest`)
- [x] A failure demo (`scripts/failure-demo.sh`)
- [x] Smoke: catalog-service stopped → add-to-cart 503 in ~0.6 s, checkout STILL 201, recovers in ~10 s

## Verified (WSL2 workstation, by Claude Code)
- `./mvnw clean verify`, stack down: 12 + 34 + 41+12 + 43+8 + 8+3 + 7+15 + 231+53, BUILD SUCCESS, 2:03.
- Smoke from a COLD stack: **313 / 0 / 0**. One earlier cold run: 310 / 3 — intermittent Phase 15/17
  checks, diagnosed in `docs/test-reports/phase-22.md` §5; `main` cold: 295 / 0.
- Full write-up: `docs/test-reports/phase-22.md` (read §0: the first policy could not meet its budget).

## Next action
**Waiting for review.** On `approved, merge it`: `gh pr merge <n> --merge` (never squash/rebase/delete),
then merge verification per `docs/process/execution-protocol.md` §5 on `main`: stack DOWN, `./mvnw clean
verify`, then a cold `docker compose up --build --wait` + `scripts/smoke-test.sh`, then
`git tag -a phase-22-complete` and push the tag. Next phase: 23 (Distributed Tracing).

### ⚠️ Environment notes (this machine)
- Windows reserves TCP **9022–9121** (WinNAT) → Prometheus' 9090 may fail to bind. Workaround:
  `export PROMETHEUS_PORT=19090 PROMETHEUS_URL=http://localhost:19090` (or the user sets it in `.env`).
- The WSL2 VM can be paused by the host (one `verify` took 86 min). If a run is absurdly slow, check
  `dmesg | grep TimeSync` and rerun before diagnosing code.
- Repo-local git identity set to `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).

## ⚠️ Carried, not fixed (oldest first)
- **Phase 19 dashboard defect** — two `EcomDemo Overview` stat panels reduce an instantaneous rate with
  `lastNotNull`. Branch `fix/dashboard-stat-reducers`.
- A failed compensating release leaks a reservation; nothing reconciles it.
- The CSV import is a distributed write with no shared transaction (restartable, idempotent).
- **HS256 with a shared secret** — every service can mint as well as verify.
- `ecomdemo-app` is not yet named `order-service`.
- catalog-service has no springdoc; a correlation ID dies at the hop; the gateway does no request logging.
- **NEW:** on a cold start a poison message stalled one notification partition until a rebalance (seen
  once in three cold runs; the Phase 17 check caught it). No message lost.
- **NEW:** the gateway's own `/api/products` route has no breaker; inventory calls (checkout's real
  dependency) have no resilience policy; notification-service idles at 97 % of its 320M cap.
- **NEW:** Phase 21 recorded no entries in `docs/decisions.md`; `application.properties` still describes
  the removed customer proxy.

# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Fix:** KI-070: the "EcomDemo Logs" dashboard queries only `{service_name="app"}` in its level, error and correlation panels, so "one request's whole story" leaves out the gateway's request line (KI-035) and every other service's
- **Branch:** fix/ki-070-logs-dashboard-all-services (cut from `main` at `a85202f`); the owner said "continue" (the next backend defect)
- **Step:** IN_PROGRESS (full verify running; docs written)
- **Waiting for user:** NO
- **History:** PR #94 (KI-035) merged at `a85202f`; the owner pushes `ki-035-fixed` (tag pushes are refused here, 403)

## Checklist
- [x] Regression test first (`LoggingStackConfigTest`): no panel query hard-codes `app`; panels 1-4 select `service_name=~"$service"`; a multi-select `service` variable (default All) over the JSON-writing services; red on main (5 of 14 failed: `7b293d6`)
- [x] Fix: `docker/grafana/dashboards/ecomdemo-logs.json` (a `service` variable, the four panels, a service column on the log panel); targeted run green (22 tests with `DashboardMetricsTest`)
- [ ] `./mvnw -pl ecomdemo-app -am verify`, then `./mvnw -B clean verify`; the JSON parses (done); Grafana 12.3.1 + Loki 3.7.8 provision it (done, throwaway containers, removed)
- [x] Docs: KNOWN_ISSUES (KI-070 fixed), decisions `[Fix KI-070]`, README's logs-dashboard paragraph; new KI-071 (the infrastructure panel misses the per-service databases)
- [ ] Push, PR, CURRENT to PR_OPEN

## Next action
Wait for `./mvnw -B clean verify` (log `~/.cache/ecomdemo-claude/ki070-verify.log`); if green, push the branch, open the PR (no `run-smoke`: a dashboard file only), put its number in KNOWN_ISSUES' KI-070 row and here, set Step PR_OPEN, commit, push, STOP.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports (and has its own `ecomdemo-backend-readonly_kafka-data` volume: never touch it). kind does not conflict.
- compose ports are loopback-only now; probe from the LAN address (`hostname -I`) to check exposure.
- kind: `docker start ecomdemo-control-plane` revives the cluster; `scripts/k8s-up.sh` loads images but does NOT restart
  pods: `kubectl -n ecomdemo rollout restart deploy` after it, or the pods keep the old code.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- Flyway checksums comments too: never edit an applied migration; on a local DB roll back by hand if you must.
- Awaitility polls on its own thread: use `pollInSameThread()` when the condition needs the caller's transaction.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

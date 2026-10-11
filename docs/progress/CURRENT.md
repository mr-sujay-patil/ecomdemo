# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Fix:** KI-071: the "EcomDemo Logs" dashboard's infrastructure panel selects `db|cache|prometheus|grafana` (the Phase 16 list), so the per-service databases Alloy ships since the split are in Loki but on no panel
- **Branch:** fix/ki-071-logs-dashboard-databases (cut from `main` at `1316474`); the owner said "continue working on both repos" (the next backend defect)
- **Step:** PR_OPEN (PR #96, no `run-smoke`: a dashboard file only)
- **Waiting for user:** YES: CI on PR #96, then `approved, merge it`
- **History:** PR #95 (KI-070) merged at `1316474`; the owner pushes `ki-070-fixed` (tag pushes are refused here, 403)

## Checklist
- [x] Regression test first (`LoggingStackConfigTest.InfrastructurePanel`): the panel's selector must select every compose service health-checked by `pg_isready` and every service in Alloy's plain-text keep rule, not a JSON stream with a level; title not the Phase 16 list; red on main (3 of 4 failed: `b11c4c2`)
- [x] Fix: panel 5 selects `{service_name=~".+", level=""}` (the complement of the `service` variable), title and description updated (`d5c9796`); targeted run green (18 + `DashboardMetricsTest` 8); JSON parses
- [x] Throwaway Grafana 12.3.1 + Loki 3.7.8 (own network, read-only mounts, removed): the panel query returns all six databases, cache, grafana; not app/gateway
- [x] Docs: KNOWN_ISSUES (KI-071 fixed, PR #96), decisions `[Fix KI-071]`, README's logs-dashboard paragraph
- [x] `./mvnw -B clean verify` green (894 tests, 0 failures; 890 + 4 new)
- [x] Push, PR #96, CURRENT to PR_OPEN
- [ ] CI green on PR #96; the owner reviews

## Next action
Wait for CI on PR #96; fix it if red. Then STOP for the owner. After the merge: merge verification; the owner pushes `ki-071-fixed` (tag pushes are refused here, 403).

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

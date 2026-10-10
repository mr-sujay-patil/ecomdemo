# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-10
- **Fix:** KI-064 and KI-066, the smoke test's `redis_cli`/`redis_cli_noauth`/`psql_query` use a host client on k8s (wrong Redis/database) and require `docker` for the in-pod path
- **Branch:** fix/ki-064-066-in-pod-clients (cut from `main` at `a4dbee7`); grouped per workflow rule 11 (same cause, same code)
- **Step:** TESTING
- **Waiting for user:** NO. Chosen after the user said "continue" (next open backend defect). Tags (`phase-36-complete` and earlier) are the user's to push (403 here).

## Checklist
- [x] Regression test `scripts/test-smoke-clients.sh` with fake `redis-cli`/`psql`/`kubectl` (fails first): on k8s the in-pod path is used even with host clients present; on compose a host client is still preferred
- [x] `scripts/smoke-clients.sh` (sourced, like `smoke-burst.sh`): the three helpers; `k8s-smoke.sh` copies it
- [x] Docs: KNOWN_ISSUES (fixed), decisions [Fix KI-064, KI-066]
- [ ] `./mvnw -B clean verify`; PR (label `run-smoke`); CI green

## Next action
Extract the three helpers unchanged into `scripts/smoke-clients.sh`, write the fake-binary test, see it fail on k8s, then fix.

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

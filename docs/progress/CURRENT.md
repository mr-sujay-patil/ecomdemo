# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-047, the gateway's management port (8088) also served the API routes
- **Branch:** fix/ki-047-mgmt-port-routes (cut from `main` at `d7be593`)
- **Step:** PR_OPEN (PR number below once raised)
- **Waiting for user:** YES (review of the KI-047 PR)

## Merge verification of KI-007 (done 2026-10-08)
PR #70 merged as `d7be593`; CI on `main` green; tag `ki-007-fixed` on origin.

## Done
- `ManagementPortGuardFilter` (gateway-service): 404 for non-`/actuator` paths on the management port, found through
  `local.management.port` + the request's local port. `EdgeSecurityIT` gained two tests (seen failing first).
- Docs: `decisions.md` [Fix KI-047], KNOWN_ISSUES row.
- `./mvnw clean verify` exit 0 (42/253/68 tests, 0 failures).
- NOT run: `scripts/smoke-test.sh` (compose or kind); k8s probes and Prometheus only use `/actuator/**`, which the filter lets through.

## Next action
STOP: wait for the user's review. After `approved, merge it`: wait for the required check, merge with `--merge`, verify (tip in
`main`, CI on `main` incl. scans and publish), tag `ki-047-fixed`. Still open: KI-008..011.
`stash@{0}` (KI-045 noise) is the user's to drop.

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

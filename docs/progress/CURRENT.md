# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-006, the gateway's `/actuator/prometheus` is public on port 8080 (route names, error rates, JVM details)
- **Branch:** fix/ki-006-gateway-prometheus-internal (cut from `main` at `723563f`)
- **Step:** BRANCHED
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification of KI-005 (done 2026-10-08)
PR #68 merged as `723563f`; tag `ki-005-fixed` exists on origin and is in `main`.

## Plan
Move the gateway's actuator to a separate management port (in-network only, never published, never routed), so the public
port 8080 no longer answers `/actuator/*` at all. Prometheus scrapes the management port.

## Checklist
- [ ] Reproduce first: a test that fails because `/actuator/prometheus` answers 200 on the public port
- [ ] Gateway `management.server.port`, security rule for prometheus removed
- [ ] compose healthcheck + Prometheus target; k8s probes + scrape; Dockerfile HEALTHCHECK is shared (check)
- [ ] smoke tests (compose + k8s) that read gateway health/metrics
- [ ] Docs: `security.md` API8, `decisions.md` [KI-006], KNOWN_ISSUES row -> Fixed
- [ ] Testing protocol (steps 1, 3, 4, 7, 8 + regression test)

## Next action
Write the failing test in `gateway-service` (`EdgeSecurityIT`), then implement. Still open: KI-007..011.
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

# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-006, the gateway's `/actuator/prometheus` is public on port 8080 (route names, error rates, JVM details)
- **Branch:** fix/ki-006-gateway-prometheus-internal (cut from `main` at `723563f`)
- **Step:** PR_OPEN
- **PR:** #69 https://github.com/mr-sujay-patil/ecomdemo/pull/69
- **Waiting for user:** YES (review of the KI-006 PR)

## Merge verification of KI-005 (done 2026-10-08)
PR #68 merged as `723563f`; tag `ki-005-fixed` exists on origin and is in `main`.

## Done
- Gateway actuator on `management.server.port` 8088 (`GATEWAY_MANAGEMENT_PORT`); 8080 answers 401 under `/actuator`.
- `EdgeSecurityIT`: both ports (seen failing before the fix: 200 on 8080). Compose healthcheck, Dockerfile HEALTHCHECK (by
  module), Prometheus target, k8s probes on a `management` container port (not in the Service). Smoke "API gateway" section.
- Docs: `security.md` API8, `decisions.md`, KNOWN_ISSUES (KI-006 fixed; KI-047 new: management port also proxies routes).
- `./mvnw clean verify` exit 0; compose cold smoke 490/0/0; Prometheus target `gateway-service:8088` up. Not run: Kubernetes.

## Next action
STOP: wait for the user's review. After `approved, merge it`: wait for the required check, merge with `--merge`, verify (tip in
`main`, CI on `main` incl. scans and publish), tag `ki-006-fixed`. Still open: KI-007..011, KI-047.
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

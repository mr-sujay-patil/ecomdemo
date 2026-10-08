# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-057, Redis TLS-only in k8s
- **Branch:** fix/ki-057-redis-tls (cut from `main` at `91821fa`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-057 PR)

## Done
- Chosen by the assistant as the smallest remaining TLS gap, after the user said "continue" (not an explicit pick; say if it was unwanted).
- Redis `--port 0 --tls-port 6379` with `cache-tls`; 4 clients `SPRING_DATA_REDIS_SSL_ENABLED`; readiness probe over TLS; `cert-reload` sidecar.
- Smoke: plain-text check + 10 certificates; `scripts/k8s-smoke.sh` 460 passed, 0 failed, 7 skipped. Forced renewal: sidecar reloaded, new serial in ~70 s, 0 restarts.
- Docs: README TLS, security.md, KNOWN_ISSUES (KI-057 fixed), `decisions.md` [Fix KI-057].

## Next action
STOP: wait for review. After `approved, merge it`: confirm CI green, merge --merge, verify in `main`, tag `ki-057-fixed`.
An existing cluster: `scripts/k8s-up.sh`, then `kubectl -n ecomdemo rollout restart deploy` (the ConfigMap change does not restart the clients).
Remaining TLS gaps: KI-058 (PostgreSQL), KI-059 (Kafka). Other: KI-016..024, 031..036, 048, 054, 055.

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

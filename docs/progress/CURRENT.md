# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-050, Redis has no password (the no-replica half is split out as KI-054)
- **Branch:** fix/ki-050-redis-password (cut from `main` at `3c7ecf5`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-050 PR; also see "User action" below)

## Merge verification of the previous units (done 2026-10-08)
KI-052 (#78, tag `ki-052-fixed`) and KI-053 (#79, tag `ki-053-fixed`) are in `main`; CI on `main` after #79 green, publish succeeded.

## Done
- compose: `--requirepass ${REDIS_PASSWORD:?...}`, `REDISCLI_AUTH`, `SPRING_DATA_REDIS_PASSWORD` for app/catalog/assistant/gateway.
  k8s: `cache-secrets`, `cache.yaml` args/env, `k8s-up.sh` adds the key to the four service Secrets. No Java changed.
- Smoke: "Redis refuses a client that has no password" + "answers one that has it" (seen failing before: unauthenticated PING gave PONG).
- kind: `scripts/k8s-smoke.sh` 448 passed, 0 failed, 7 skipped. Docs: KI-050 fixed + KI-054, `security.md`, `decisions.md`, README, `.env.example`.
- No local full `./mvnw verify`: no Java or pom change; the PR's CI "Build and test" runs it.

## User action (not code)
Add `REDIS_PASSWORD=<openssl rand -hex 16>` to `.env` before the next `docker compose up`; compose refuses to start without it.
The frontend team needs the same variable. Existing kind cluster: after `scripts/k8s-up.sh`, `kubectl -n ecomdemo rollout restart deploy`.

## Next action
STOP: wait for review. After `approved, merge it`: confirm CI green, merge --merge, verify in `main`, tag `ki-050-fixed`.
Remaining gaps: KI-016..024, 031..036, 048, 049, 051, 054.

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

# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Unit:** chore/align-process-docs (PR open): align `docs/process/*` with the workflow rules in `CLAUDE.md`
- **Branch:** chore/align-process-docs (cut from `main` at `82ef598`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the chore PR)

## State of the backend
No open defects. Last merged fixes: KI-050 (Redis password, `c0f0189`, tag `ki-050-fixed`) and KI-049 (smoke test in CI, `82ef598`,
tag `ki-049-fixed`). Open deferred gaps: KI-016..024, 031..036, 048 (needs a Sonar server/token from the user),
051 (TLS), 054, 055.

## Next action
STOP: wait for review. After `approved, merge it`: confirm CI green, merge --merge, verify in `main` (no tag for a chore).
Then wait for the user to pick the next unit (the user suggested TLS, KI-051, as the one worth doing; its scope needs agreeing first).
The frontend team was given notes for everything since `phase-34-complete` (pull `main`, add `REDIS_PASSWORD`, paging, 503s, actuator).

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

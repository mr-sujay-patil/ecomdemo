# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-04
- **Fix:** KI-082 (smoke checks that pipe into `grep -q` under `set -o pipefail` can fail with the match present). The owner: "continue with the best recommendation according to you"
- **Branch:** fix/ki-082-smoke-retry-after-pipe (cut from `main` at `9ce4adf`)
- **Step:** PR_OPEN (#113), waiting on CI
- **Waiting for user:** no
- **History:** KI-032 (PR #112) merged as `9ce4adf`, merge-verified 2026-11-04 (`./mvnw -B test` on `main`: 0 failures, the 29 Docker-only errors). Web chore PR #63 (KI-030/031 rows) merged as `3afbfbc`, verified (local verify 1111 tests with Node 24; CI on `main` green). Tags for the owner (403 here): `ki-032-fixed` `9ce4adf`, `ki-031-fixed` `d939733`, `ki-073-fixed` `b712df7`, `ki-076-fixed`/`ki-079-fixed` `f68f00b`, `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`

## Checklist
- [x] KI-082 In progress, scope: all 33 piped `grep -q` in `smoke-test.sh`; KI-083 recorded (stale app.yaml comment, not fixed here)
- [x] Red first: `scripts/test-smoke-pipes.sh` (4 of 7 red on `main`; the race itself reproduced 3 of 3)
- [x] Fix: `scripts/smoke-pipes.sh` (`contains`), sourced by smoke-test.sh beside smoke-clients.sh; 33 `| grep -q<flags>` became `| contains <flags>`; `bash -n` clean; all 5 `test-smoke-*.sh` pass; no Java test reads the text; docs (KNOWN_ISSUES, decisions)
- [x] PR #113 with `run-smoke`
- [ ] CI green, then STOP for review (the owner may run the k8s smoke: 10 converted checks are k8s-only)

## Next action
Watch PR #113 CI; fix anything red; when green, STOP for the owner's review.

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

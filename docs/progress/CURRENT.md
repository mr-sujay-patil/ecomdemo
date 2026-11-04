# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-04
- **Fix:** KI-083 (`k8s/services/app.yaml`'s header argues one replica from a double-publishing relay that fix KI-002's lock prevents), with KI-033 moved to Accepted limits (the comment's remaining reason). The owner: "continue on the next task"
- **Branch:** fix/ki-083-app-one-replica-reasons (cut from `main` at `eeaa277`)
- **Step:** BRANCHED
- **Waiting for user:** no
- **History:** KI-082 (PR #113) merged as `eeaa277`, merge-verified 2026-11-04 (tip `f191648` in `main`; the 5 `test-smoke-*.sh` pass; `./mvnw -B test` 0 failures, 29 Docker-only errors). Web PR #65 (KI-047) approved, merges when its CI is green. Tags for the owner (403 here): `ki-082-fixed` `eeaa277`, `ki-032-fixed` `9ce4adf`, `ki-031-fixed` `d939733`, `ki-073-fixed` `b712df7`, `ki-076-fixed`/`ki-079-fixed` `f68f00b`, `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`

## Checklist
- [x] Re-checked each app schedule: relay (KI-002 lock: one publisher), outbox cleanup (idempotent DELETE), saga sweep (KI-038, accepted), sales report (KI-033: a second run for the day is refused)
- [ ] Fix: app.yaml header states the real reasons (every replica would run every job: duplicate work and a refused report logged as an error each night; correctness holds); KI-033 to Accepted limits; KI-083 Fixed; decisions
- [ ] No automated test (a comment's wording): said in the PR; `kubectl apply --dry-run` not available here, so a YAML parse check
- [ ] PR, CI, STOP

## Next action
Rewrite the app.yaml header comment.

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

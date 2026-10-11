# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-11
- **Fix:** KI-069: `scripts/test-smoke-guard.sh` printed its "saw:" diagnostic twice (the line was duplicated); cosmetic
- **Branch:** fix/ki-069-guard-diagnostic-once (cut from `main` at `8a4ffd8`); the owner said "continue" (the next backend defect)
- **Step:** PR_OPEN (PR #97, no `run-smoke`: a test script only)
- **Waiting for user:** YES: CI on PR #97, then `approved, merge it`
- **History:** PR #96 (KI-071) merged as `8a4ffd8`; the owner pushes `ki-071-fixed` (tag pushes are refused here, 403)

## Checklist
- [x] Regression check first: `test-smoke-guard.sh` asserts exactly one line prints the `saw:` diagnostic; red on main (`830fca6`)
- [x] Fix: duplicate line deleted (`52d4062`); all four `scripts/test-smoke-*.sh` green, `bash -n` clean
- [x] Docs: KNOWN_ISSUES (KI-069 fixed, PR #97), decisions `[Fix KI-069]`
- [x] `./mvnw -B clean verify` not run locally (no Java/build change); CI's `build` runs it
- [x] Push, PR #97, CURRENT to PR_OPEN
- [ ] CI green on PR #97; the owner reviews

## Next action
Wait for CI on PR #97; fix it if red. Then STOP for the owner. After the merge: merge verification; the owner pushes `ki-069-fixed` (tag pushes are refused here, 403).

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

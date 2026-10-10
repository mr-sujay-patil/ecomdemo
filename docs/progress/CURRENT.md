# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-10
- **Fix:** KI-055, the smoke test's rate-limit burst check counts only 200 as "served"
- **Branch:** fix/ki-055-burst-counts-admitted (cut from `main` at `bf0c109`)
- **Step:** PR_OPEN (PR #90, label `run-smoke`)
- **Waiting for user:** YES: CI on PR #90, then `approved, merge it`. Chosen by the assistant after the user said "continue" (offered earlier as the default). Tags `ki-059-fixed`, `phase-35-complete` and older ones are for the user to push (403 here).

## Root cause (evidence: burst lines of `smoke.yml` runs 2 to 7)
Admitted requests (not 429) are 100 to 150 every run; the non-200 part of them is `503` from the catalog route's circuit-breaker fallback (2 s timeout) on a cold runner, 0 to 39 per run. Run 1 failed when too many admitted requests were 503s. The limiter never banned.

## Checklist
- [x] Regression test `scripts/test-smoke-burst.sh` (fails first): the 6 real distributions pass, a run-1-like cold burst passes, a real ban fails, no 429 fails
- [x] `scripts/smoke-burst.sh`: the verdict, sourced by `smoke-test.sh`; the check counts admitted requests and names the 503s
- [x] KI-062 recorded (the 503s themselves; Needs check), not fixed here
- [x] Docs: KNOWN_ISSUES (fixed), decisions [Fix KI-055]
- [x] `./mvnw -B clean verify` (846 tests); PR #90
- [ ] CI green on PR #90, including the `run-smoke` compose smoke test

## Next action
Wait for CI on PR #90 (with `run-smoke`, the compose smoke test runs the burst on this branch); fix it if red. Then STOP for the owner. After the merge: merge verification; the owner pushes `ki-055-fixed`.

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

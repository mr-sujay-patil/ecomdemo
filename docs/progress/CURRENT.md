# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-049, the smoke test did not run in CI
- **Branch:** fix/ki-049-smoke-test-in-ci (cut from `main` at `c0f0189`)
- **Step:** PR_OPEN (PR #81, labelled `run-smoke`)
- **Waiting for user:** YES (review of the KI-049 PR)

## Merge verification of KI-050 (done 2026-10-08)
PR #80 merged as `c0f0189`, tag `ki-050-fixed`. The user must add `REDIS_PASSWORD` to `.env` (compose refuses to start without it).

## Done
- `.github/workflows/smoke.yml`: daily 05:43 UTC + workflow_dispatch + PR label `run-smoke`; throwaway `.env`, `docker compose up -d --build --wait`,
  `scripts/smoke-test.sh`, logs artifact, teardown. User chose this schedule (2026-10-08). `run-smoke` label created.
- Proven on CI: 4 runs of the same code, 1 failed (rate-limit burst, cold runner), 3 passed 468/0/3. Burst counts now printed. KI-055 records the flake.
- README "The smoke test in CI", KNOWN_ISSUES (KI-049 fixed, KI-055), `decisions.md` [Fix KI-049].

## Next action
STOP: wait for review. After `approved, merge it`: confirm CI green, merge --merge, verify in `main`, tag `ki-049-fixed`.
The first daily run on `main` happens at 05:43 UTC; the workflow can also be started from the Actions tab.
Remaining gaps: KI-016..024, 031..036, 048, 051, 054, 055.

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

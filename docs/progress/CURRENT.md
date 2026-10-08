# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-011, dead `reserve`/`release` in the inventory gateway and inventory-service's HTTP API
- **Branch:** fix/ki-011-remove-dead-reserve-release (cut from `main` at `99fc130`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-011 PR)

## Merge verification of KI-010 (done 2026-10-08)
PR #74 merged as `99fc130`; CI on `main` green; tag `ki-010-fixed`.

## Done
- Removed `reserve`/`release` from `InventoryGateway`, `InventoryClient`, `ResilientInventory`, and the two inventory-service
  endpoints + `UnitsRequest`. Regression test `InventorySecurityTest.reserveAndReleaseAreGone` (404; seen failing with 409 on the old controller).
- Removed tests of the removed behaviour (ResilientInventoryTest reserve/release cases, 3 reserve/release validation tests,
  `never().reserve/release` assertions); security tests moved to the remaining `PUT /api/inventory/{id}` write.
- KI-052 added: `InventoryService.reserve/release` still exist, test-only. `decisions.md` [Fix KI-011]; README and `saga.md` updated.
- `./mvnw clean verify` green; `scripts/k8s-smoke.sh` on kind: 446 passed, 0 failed, 7 skipped.

## Pending request from the user (2026-10-08)
Document the backend changes since `phase-34-complete` for the frontend team and tell them to point to `main`.
Draft is in the job tmp dir (`frontend-changes.md`); commit it on a `chore/` branch AFTER this PR merges (one open PR at a time).

## Next action
STOP: wait for review. After `approved, merge it`: wait for checks, merge --merge, verify, tag `ki-011-fixed`. Then the chore branch above.
Still open: KI-052.

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

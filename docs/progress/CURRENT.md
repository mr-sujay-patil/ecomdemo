# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-052, `InventoryService.reserve`/`release` have no production caller
- **Branch:** fix/ki-052-remove-unused-inventory-reserve-release (cut from `main` at `33d6f1a`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-052 PR)

## Merge verification of the previous units (done 2026-10-08)
KI-011 PR #75 `f088fd4`, tag `ki-011-fixed`. Chore PRs #76 (workflow rules in CLAUDE.md) and #77 (CI: tests on PRs only) merged;
first `main` run after #77: build skipped, scans green, publish succeeded.

## Done
- Removed `InventoryService.reserve`/`release`. Tests re-homed: `StockChangePublisherTest` calls `publish` directly (H2),
  `ConcurrentReservationTest` races two orders through `reserveForOrder` (PostgreSQL), `InventorySagaTest` gained
  "announces stock-changed". The five H2 `Reserving` cases in `InventoryServiceTest` were removed (the method is gone and
  `reserveForOrder` needs PostgreSQL; covered by `InventorySagaTest`). KNOWN_ISSUES KI-052 Fixed, `decisions.md` [Fix KI-052].
- `./mvnw -B verify` green. Smoke test skipped on purpose (workflow rule 5: no manifest/config/startup/inter-service change).

## Next action
STOP: wait for review. After `approved, merge it`: merge --merge, verify it is in `main`, tag `ki-052-fixed`.
No open defects remain after this; the rest of KNOWN_ISSUES is deferred gaps (KI-016..024, 031..036, 048..051).

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

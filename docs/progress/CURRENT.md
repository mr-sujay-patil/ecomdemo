# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-008, nothing prunes `processed_event`
- **Branch:** fix/ki-008-prune-processed-event (cut from `main` at `83a4a20`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-008 PR)

## Merge verification of KI-047 (done 2026-10-08)
PR #71 merged as `83a4a20`; CI on `main` green incl. scans and publish; tag `ki-047-fixed`.

## Done
- outbox library: `ProcessedEventRepository.deleteProcessedBefore`, `OutboxProperties.processedEventRetention` (30 d),
  `OutboxCleanupJob` prunes in its existing 03:00 sweep. Tests: `OutboxCleanupJobTest`, `OutboxPropertiesTest`,
  `ProcessedEventRepositoryTest` (new, DB). Red phase was a compile failure (the new members did not exist yet).
- Docs: `decisions.md`, `saga.md`, KNOWN_ISSUES.

## Next action
STOP: wait for review. `./mvnw clean verify` exit 0 (app 255, gateway 68 tests). After `approved, merge it`: wait for checks, merge --merge, verify, tag `ki-008-fixed`.
notification-service's own table is not pruned (KI-010). Still open: KI-009..011.

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

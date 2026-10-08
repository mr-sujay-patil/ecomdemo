# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-010, notification-service keeps its own copy of the idempotent-consumer code
- **Branch:** fix/ki-010-notification-processed-events (cut from `main` at `b3ed529`)
- **Step:** PR_OPEN
- **Waiting for user:** YES (review of the KI-010 PR)

## Merge verification of KI-009 (done 2026-10-08)
PR #73 merged as `b3ed529`; CI on `main` green; tag `ki-009-fixed`.

## Done
- User chose "adopt @EnableOutbox" (2026-10-08). notification-service now depends on `ecomdemo-outbox`, has `@EnableOutbox`,
  an empty `OutboxRoutes` bean (`OutboxWiring`), and `V2__outbox_event.sql` (always-empty table).
- Deleted its `EventDeduplicator`, `ProcessedEvent`, `ProcessedEventRepository`, `EventDeduplicatorTest`
  (`ProcessedEventsTest` in the library covers the same behaviour); `NotificationService` uses `ProcessedEvents`.
- Docs: KNOWN_ISSUES KI-010 Fixed, `decisions.md` [Fix KI-010], `saga.md` gaps list.
- `./mvnw clean verify`: BUILD SUCCESS, 0 failures (notification-service: 6 unit + 5 IT).

## Verified (2026-10-08)
- Steps 3-4 ran on the kind cluster (the frontend team's compose stack was left alone): only the notification image was
  rebuilt/loaded and the deployment restarted. New pod applied V2, started in 5 s, 0 ERROR lines.
  `scripts/k8s-smoke.sh`: 446 passed, 0 failed, 7 skipped (compose-only checks).
- Side effect: `docker compose build notification-service` retagged the local `ecomdemo-notification:latest`.

## Next action
STOP: wait for review. After `approved, merge it`: wait for checks, merge --merge, verify, tag `ki-010-fixed`.
Still open: KI-011.

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

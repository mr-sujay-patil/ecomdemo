# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-08
- **Fix:** KI-010, notification-service keeps its own copy of the idempotent-consumer code
- **Branch:** fix/ki-010-notification-processed-events (cut from `main` at `b3ed529`)
- **Step:** SMOKE_PENDING (code + docs done, `./mvnw clean verify` green; PR not raised yet)
- **Waiting for user:** YES (steps 3-4 of the testing protocol need a free compose stack, see below)

## Merge verification of KI-009 (done 2026-10-08)
PR #73 merged as `b3ed529`; CI on `main` green; tag `ki-009-fixed`.

## Done
- User chose "adopt @EnableOutbox" (2026-10-08). notification-service now depends on `ecomdemo-outbox`, has `@EnableOutbox`,
  an empty `OutboxRoutes` bean (`OutboxWiring`), and `V2__outbox_event.sql` (always-empty table).
- Deleted its `EventDeduplicator`, `ProcessedEvent`, `ProcessedEventRepository`, `EventDeduplicatorTest`
  (`ProcessedEventsTest` in the library covers the same behaviour); `NotificationService` uses `ProcessedEvents`.
- Docs: KNOWN_ISSUES KI-010 Fixed, `decisions.md` [Fix KI-010], `saga.md` gaps list.
- `./mvnw clean verify`: BUILD SUCCESS, 0 failures (notification-service: 6 unit + 5 IT).

## Not done
- Testing protocol steps 3-4 (run the app, `scripts/smoke-test.sh`): the frontend team's stack
  (`~/projects/ecomdemo-backend-readonly`) is running on the same container names and ports. NOT touched.

## Next action
Ask the user how to run steps 3-4 (stop the other stack, or use the kind cluster). Then smoke, put results in the PR
description, raise the PR, STOP. After `approved, merge it`: wait for checks, merge --merge, verify, tag `ki-010-fixed`.
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

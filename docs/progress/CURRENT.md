# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-11-04
- **Fix:** KI-031 (nothing watches the outbox: no pending gauge, no alert). Re-checked against the code and approved 2026-11-04 ("re-check backend KI-031 ... turn it into a fix if it's still real")
- **Branch:** fix/ki-031-outbox-backlog (cut from `main` at `b712df7`)
- **Step:** BRANCHED
- **Waiting for user:** no
- **History:** KI-073 (PR #109) merged as `b712df7`, merge-verified 2026-11-04 (branch tip `3bc9057` in `main`; `./mvnw -pl ecomdemo-app -am test` on `main`: 448 tests, 0 failures). Tags for the owner (403 here): `ki-073-fixed` `b712df7`, `ki-076-fixed`/`ki-079-fixed` `f68f00b`, `ki-080-fixed` `f872e3d`, `ki-078-fixed` `d69b738`, `ki-077-fixed` `8d36b19`, `ki-074-fixed`/`ki-075-fixed` `e0a13eb`, `phase-37-complete` `4767124`. Waiting after this fix: web chore for KI-030/031's stale rows (web PR #61 open, one PR at a time)

## Checklist
- [x] Re-checked: `OutboxObservationConfig` only drops the relay's traces; `countByPublishedAtIsNull()` used only by tests; no outbox rule in `docker/prometheus/alerts.yml`. Moved from "Needs check" to the fix queue (Medium)
- [ ] Red first: an outbox unit test for the two gauges (`outbox.events.pending`, `outbox.events.oldest.pending.age`) refreshed by the relay's tick; DashboardMetricsTest knows them and the alert rule references them
- [ ] Fix: `OutboxMetrics` in the outbox library (one query per relay tick: count and oldest `created_at` of unpublished rows; age computed at scrape), the relay refreshes it after each tick (also when the tick fails, the last values stay), alert `OutboxEventsStuck` in `docker/prometheus/alerts.yml`. No health indicator (a backlog must not take instances out of readiness)
- [ ] Tests (outbox and app unit locally; ITs in CI), docs (KNOWN_ISSUES, decisions, README "Known gaps", observability docs), PR

## Next action
Write the red tests in the outbox module and DashboardMetricsTest, then implement.

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

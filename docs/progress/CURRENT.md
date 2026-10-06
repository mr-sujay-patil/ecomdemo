# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Fix:** KI-002, two outbox relays double-publish (catalog-service runs 2-4 pods)
- **Branch:** fix/ki-002-outbox-relay-lock (cut from `main` at `adf6f72`)
- **Step:** PR_OPEN
- **PR:** #65 https://github.com/mr-sujay-patil/ecomdemo/pull/65
- **Waiting for user:** YES (review of PR #65)

## Merge verification of KI-044 (done 2026-10-06)
PR #64 merged as `adf6f72`; CI on `main` all green incl. publish; tag `ki-044-fixed`. The first SCHEDULED run
(04:43 UTC) is still to be checked: `gh run list --workflow ci.yml --event schedule`.

## Done
- Advisory lock (`pg_try_advisory_xact_lock`, `OutboxEventRepository.RELAY_LOCK_KEY`) at the start of each batch in
  `OutboxBatchPublisher`; chosen over SKIP LOCKED because disjoint rows let two relays reorder one order's events.
- Tests: `OutboxBatchPublisherTest.yieldsToAnotherRelay`, `OutboxRelayLockIT` (seen failing without the fix; its first
  version was flawed: Awaitility polls on another thread, use `pollInSameThread()`).
- `./mvnw clean verify` exit 0. kind smoke 436/0/7. Burst of 80 creates over 3 autoscaled pods: 80 messages, 80 distinct.
  History before the fix: 328 extra copies in 1,136 messages on `catalog.product-changed`.
- Not run: compose smoke (frontend team's stack was up). Consumers audited for duplicates (in the PR).
- I started the stopped kind control plane to test, then stopped it again (`docker stop ecomdemo-control-plane`).

## Next action
STOP: wait for the user's review of PR #65. After `approved, merge it`: verify the merge (tip in `main`, CI on `main`
incl. scans and publish), a cold compose smoke if no foreign stack is up (also owed for KI-040), tag `ki-002-fixed`.
Still open: KI-003..011. `stash@{0}` (KI-045 noise) is obsolete; the user's to drop.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports (and has its own `ecomdemo-backend-readonly_kafka-data` volume: never touch it). kind does not conflict.
- kind: `docker start ecomdemo-control-plane` revives the cluster; `scripts/k8s-up.sh` loads images but does NOT restart
  pods: `kubectl -n ecomdemo rollout restart deploy` after it, or the pods keep the old code.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- Flyway checksums comments too: never edit an applied migration; on a local DB roll back by hand if you must.
- Awaitility polls on its own thread: use `pollInSameThread()` when the condition needs the caller's transaction.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

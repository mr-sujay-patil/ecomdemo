# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Fix:** KI-044 (a suggestion the user asked for): run the dependency and image scans daily and on demand
- **Branch:** fix/ki-044-scheduled-scans (cut from `main` at `fa60749`)
- **Step:** PR_OPEN
- **PR:** #64 https://github.com/mr-sujay-patil/ecomdemo/pull/64
- **Waiting for user:** YES (review of PR #64)

## Merge verification of KI-040 (done 2026-10-06)
PR #63 merged as `fa60749`; tip in `main`, CI on `main` all green, `./mvnw clean verify` exit 0, tag `ki-040-fixed`.
The cold compose smoke on `main` was NOT run: the frontend team's stack was up (same names and ports). Run it when free.

## Done
- `ci.yml`: `schedule` (04:43 UTC) and `workflow_dispatch`; `build` skips on them, `publish` already push-only; scheduled and
  manual runs have their own concurrency group (a scan must not cancel a queued publish).
- A manual dispatch on the branch (run 37458893489): both scans passed, build and publish skipped.
- Docs: `docs/security.md`, README, `docs/decisions.md`, KNOWN_ISSUES row.

## Next action
STOP: wait for the user's review of PR #64. After `approved, merge it`: verify the merge (tip in `main`, CI on `main`
incl. publish, which must still run on the push), tag `ki-044-fixed`. The first SCHEDULED run is the next 04:43 UTC:
check it (`gh run list --workflow ci.yml --event schedule`). No smoke test: CI config only.
Still open: KI-002..011. `stash@{0}` (KI-045 noise) is obsolete; the user's to drop.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports (and has its own `ecomdemo-backend-readonly_kafka-data` volume: never touch it). kind does not conflict.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- Flyway checksums comments too: never edit an applied migration; on a local DB roll back by hand if you must.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

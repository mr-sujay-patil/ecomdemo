# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Fix:** KI-040, a dead letter's replay identity was its Kafka address only, so reused offsets were refused 409 for ever
- **Branch:** fix/ki-040-dead-letter-identity (cut from `main` at `ba475ed`)
- **Step:** PR_OPEN
- **PR:** #63 https://github.com/mr-sujay-patil/ecomdemo/pull/63
- **Waiting for user:** YES (review of PR #63)

## Merge verification of KI-045 (done 2026-10-06)
PR #62 merged as `ba475ed`; tip in `main`, CI on `main` all green; tag `ki-045-fixed`; working tree clean.

## Done
- V21 `dlt_timestamp` in the unique key; `replay()` fetches first, then checks address + timestamp (`DeadLetterReplayRepository.isReplayed`).
- A pre-V21 row (NULL) blocks only a record written at or before that replay. Tests written red first (`DeadLetterIT` 6/6).
- `./mvnw clean verify` exit 0. Compose smoke 482/0/0 on kept volumes (V21 applied over V20 rows) and 482/0/0 after wiping
  this stack's own `ecomdemo_kafka-data` with the database kept (the defect's scenario; it was 429/2 failed).
- Not run: Kubernetes smoke, cold `down -v` compose run (schema-only change, no manifests).
- Docs: README, `architecture/saga.md`, `decisions.md`, KNOWN_ISSUES row.

## Next action
STOP: wait for the user's review of PR #63. After `approved, merge it`: verify the merge (tip in `main`, CI on `main`
incl. scans and publish), a cold compose smoke (`down -v`, `up --build --wait`), tag `ki-040-fixed`.
Still open: KI-044, KI-002..011. `stash@{0}` (KI-045 noise) is obsolete; the user's to drop.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports (and has its own `ecomdemo-backend-readonly_kafka-data` volume: never touch it). kind does not conflict.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- Flyway checksums comments too: never edit an applied migration; on a local DB roll back by hand if you must.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

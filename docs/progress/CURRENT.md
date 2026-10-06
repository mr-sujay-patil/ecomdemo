# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Fix:** KI-046, flaky `LoginThrottleIT.blocksAClientTryingManyUsernames` (cause: `address()` collided, 1 run in 250)
- **Branch:** fix/ki-046-login-throttle-flake (cut from `main` at `40020ef`)
- **Step:** PR_OPEN
- **PR:** #61 https://github.com/mr-sujay-patil/ecomdemo/pull/61
- **Waiting for user:** YES (review of PR #61)

## Done
- Root cause found by reading the code, confirmed by forcing a collision (constant address): 429 where 401/200 expected.
- `ClientAddressesTest` written first and seen failing; `support/ClientAddresses` (counter, random start); `LoginThrottleIT` uses it.
- `./mvnw clean verify` exit 0; `LoginThrottleIT` 3/3, `ClientAddressesTest` 2/2.

## Next action
STOP: wait for the user's review of PR #61. After `approved, merge it`: merge verification (branch tip in `main`, CI on
`main` incl. scans and publish, `./mvnw clean verify`), tag `ki-046-fixed`. No compose smoke needed: test-only change.
Not yet confirmed: CI on `main` for `40020ef` (PR #60 merge) was still running when this fix was started.
Still open: KI-040, KI-044, KI-045 (mvnw.cmd eol; `stash@{0}`), KI-002..011.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports. Never stop or `down` their project; ask the user. kind does not conflict.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

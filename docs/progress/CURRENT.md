# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Phase:** none in progress. Phase 34 (Product Images) is merged and verified (tag `phase-34-complete`).
- **Branch:** chore/phase-34-closeout (cut from `main` at `9173309`; docs only, asked for by the user)
- **Step:** PR_OPEN once the PR is raised (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** not raised yet
- **Waiting for user:** no (after the PR: YES, review)

## Merge verification of Phase 34 (done 2026-10-06)
PR #59 merged as `9173309` after a re-run of one flaky CI job (KI-046). Branch tip is in `main`, trees equal.
CI on `main` all green (Build and test, both scans, Publish). `./mvnw clean verify` exit 0 on `main`.
Cold compose smoke 480 / 0 / 0. Tag `phase-34-complete` pushed. The web team's reply is drafted
(`imageUrl` is a path relative to the gateway origin; null for products 9 and 10 and API-created ones).

## This chore
- [x] Tracker row 34 to ✅ in `docs/ROADMAP.md`
- [x] KI-046 (flaky `LoginThrottleIT`) added to `docs/KNOWN_ISSUES.md`
- [x] This checkpoint reset
- [ ] Push, raise the PR, STOP for review

## Next action
Raise the PR for this branch and STOP. After `approved, merge it`: verify the merge (branch tip in `main`,
CI on `main`), then wait for the user to name the next unit of work. Still open: KI-040, KI-044, KI-045
(mvnw.cmd eol; `stash@{0}` holds the noise), KI-046, KI-002..011.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports. Never stop or `down` their project; ask the user. kind does not conflict.
- Verification uses a COLD stack (`down -v`, `up --build --wait`, `.smoke-state` removed).
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` (the stack guard needs the repo path) and delete it after.
- Never print `.env`.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

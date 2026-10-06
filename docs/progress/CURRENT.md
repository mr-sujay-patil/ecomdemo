# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Fix:** KI-043 — `scripts/smoke-test.sh` does not check the stack it hits is this checkout's own
- **Branch:** fix/ki-043-smoke-stack-guard (cut from `main` at `a926ba3`)
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this fix
KI-042: PR #56 merged as a merge commit (`a926ba3`), tag `ki-042-fixed` exists. The cold compose smoke
on main was not run (frontend team's stack held the ports then; `docker ps` now shows no compose stack).

## Checklist (KI-043)
- [x] Reproduce first: `scripts/test-smoke-guard.sh` (fake `docker`/`curl` on PATH); failed 4/6 on the unfixed script
- [x] Fix: guard before any container/network call; exit 2 if `ecomdemo-app`/`ecomdemo-gateway` label
      `com.docker.compose.project.working_dir` is another checkout; `SMOKE_ALLOW_FOREIGN_STACK=1` overrides
- [x] Guard test 6/6; `./mvnw clean verify` 701 tests, 0 failed, 0 skipped (with Maven 3.10.0 from #57)
- [x] Cold compose smoke on this branch (own stack, `down -v` + `up --build --wait`): 468/0/0, no REFUSING;
      stack taken down afterwards
- [x] Docs: decisions `[KI-043]` x2, development-environment note, KI-043 Fixed
- [ ] Push, PR "Fix KI-043", add PR number to the KI row

## Next action
Push, `gh pr create --base main`, set KI-043 to "Fixed (PR #n)", STOP for review. Merge main (#57) was merged
into this branch (no rebase). Side finding: `mvnw.cmd` in main has line endings that disagree with
`.gitattributes` (`*.cmd eol=crlf`): worktree shows a whitespace-only diff; not committed here (KI candidate).

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports. Never stop or `down` their project; ask the user. kind does not conflict.
- Verification uses a COLD stack (`down -v`, `up --build --wait`, `.smoke-state` removed).
- Never edit `scripts/smoke-test.sh` while it runs; run a copy. Never print `.env`.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

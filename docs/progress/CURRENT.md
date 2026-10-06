# Current Checkpoint

> The single source of truth for **in-fix** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Fix:** KI-045, `mvnw.cmd` blob had CRLF against `.gitattributes` (`*.cmd text eol=crlf`), so every checkout showed it modified
- **Branch:** fix/ki-045-mvnw-cmd-eol (cut from `main` at `aa84638`)
- **Step:** PR_OPEN
- **PR:** #62 https://github.com/mr-sujay-patil/ecomdemo/pull/62
- **Waiting for user:** YES (review of PR #62)

## Merge verification of KI-046 (done 2026-10-06)
PR #61 merged as `aa84638`; tip in `main`, CI on `main` all green (incl. scans and publish); tag `ki-046-fixed`.

## Done
- `git add --renormalize mvnw.cmd` in its own commit: blob is LF, checkout CRLF (`i/lf w/crlf`). Content unchanged
  (empty diff with `--ignore-space-at-eol`). Nothing else in the repo needed renormalizing.
- A fresh clone of the branch shows no modified files; so does this working tree.

## Next action
STOP: wait for the user's review of PR #62. After `approved, merge it`: verify the merge (tip in `main`, CI on `main`),
tag `ki-045-fixed`. No smoke test: line endings only. `stash@{0}` ("KI-045 mvnw.cmd eol noise") is now obsolete; it is
the user's to drop (dropping is irreversible, so ask first).
Still open: KI-040, KI-044, KI-002..011.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports. Never stop or `down` their project; ask the user. kind does not conflict.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy placed inside `scripts/` and delete it after. Never print `.env`.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

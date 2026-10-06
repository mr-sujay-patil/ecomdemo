# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-06
- **Fix:** KI-043 — `scripts/smoke-test.sh` does not check the stack it hits is this checkout's own
- **Branch:** fix/ki-043-smoke-stack-guard (cut from `main` at `a926ba3`)
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this fix
KI-042: PR #56 merged as a merge commit (`a926ba3`), tag `ki-042-fixed` exists. The cold compose smoke
on main was not run (frontend team's stack held the ports then; `docker ps` now shows no compose stack).

## Checklist (KI-043)
- [ ] Reproduce first: a scripted test (fake `docker` on PATH reporting a foreign compose working dir)
      that fails on the unfixed script: it must refuse before any HTTP call or container stop
- [ ] Fix: a guard before "Readiness" on the compose platform: `ecomdemo-*` containers must carry
      `com.docker.compose.project.working_dir` equal to this checkout; else exit 2, no traffic.
      Override `SMOKE_ALLOW_FOREIGN_STACK=1`
- [ ] Run the guard test + shellcheck-level syntax check; real smoke only if no foreign stack is up
- [ ] testing protocol steps 1, 3, 4, 7, 8; docs (decisions `[KI-043]`, development-environment note);
      KI-043 Fixed; PR

## Next action
Write the regression test `scripts/test-smoke-guard.sh`, see it fail, then add the guard.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports. Never stop or `down` their project; ask the user. kind does not conflict.
- Verification uses a COLD stack (`down -v`, `up --build --wait`, `.smoke-state` removed).
- Never edit `scripts/smoke-test.sh` while it runs; run a copy. Never print `.env`.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

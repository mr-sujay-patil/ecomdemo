# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-30
- **Fix:** KI-041 — CORS preflight refused at the gateway
- **Branch:** fix/ki-041-cors-preflight (cut from `main` at `82e5149`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #53 https://github.com/mr-sujay-patil/ecomdemo/pull/53
- **Waiting for user:** YES (review of PR #53)

## Merge verification before this fix — PASSED (tag `ki-001-fixed` on `82e5149`)
- KI-001: PR #52 `82e5149`, merge commit (2 parents). 0 missing commits, 0 diffs, branch alive
  locally and on GitHub. CI on main green: run 36671293090 (Build, Trivy, Dependency-Check, GHCR).
- On main: `./mvnw clean verify` 668 tests, 0 failed/errored/skipped. Cold smoke (`down -v`) 452/0/0.
- KI-041 was found after KI-001's merge (frontend integration guide) and is logged in this
  branch's housekeeping commit.

## Checklist (from KI-041's "Fix scope" and "Done when")
- [x] Reproduce first (`CorsIT`, 4/5 red: 401; commit `48a0a4b`): gateway IT - preflight from an allowed origin (login with Content-Type,
      cart with Authorization) gets 200 + allow headers; foreign origin refused (403);
      real authenticated request carries exactly one Access-Control-Allow-Origin
- [x] Fix: `.cors(...)` in `GatewaySecurityConfig`, built from the gateway's `globalcors` config
      (one source: application.yml / `CORS_ALLOWED_ORIGINS`)
- [x] Smoke additions: the same three checks against the running stack
- [x] README CORS paragraph, `docs/decisions.md` (`[KI-041] Decision: ...`)
- [x] Testing: verify 673 (506 unit, 167 IT) 0 failed; cold compose smoke 457/0/0; kind (9 Deployments
      restarted) 413/0/7; cleaned up. KI-041 → Fixed
- [x] PR #53; KI-041 status "Fixed (PR #53)"

## Next action
PR #53 is open. STOP: wait for the user's review. After the merge: merge verification (git checks,
CI on main, `./mvnw clean verify` + cold `down -v` smoke on main), tag `ki-041-fixed`, then ask the
user what is next (Phase 33, KI-039, KI-040). Tell the frontend (ecomdemo-web) that backend
`ki-041-fixed` exists: cross-origin browser calls work from then on.

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- No resource rationing: tests may run with the stack up. Verification uses a COLD stack
  (`down -v`, `up --build --wait`, `.smoke-state` removed): kept volumes hit KI-039/KI-040.
- kind: `SKIP_BUILD=1 scripts/k8s-up.sh` does NOT restart pods; run `kubectl rollout restart` on
  all Deployments after it, or the old images keep running.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy.
- The user's `.env` sets AI_CHAT_PROVIDER=ollama and AI_EMBEDDING_PROVIDER=ollama. Never print `.env`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>` (matches history).
- In this Claude shell `grep` is a broken Claude Code wrapper function: use `command grep`.
- Other folders: `~/projects/ecomdemo-frontend` (earlier frontend, untouched) and a planned new
  frontend `ecomdemo-web` that reads this repo read-only. Neither is changed from here.

## ⚠️ Carried, not fixed
Tracked in `docs/KNOWN_ISSUES.md` (open rows: KI-002..011, KI-039, KI-040, and the triage sections).

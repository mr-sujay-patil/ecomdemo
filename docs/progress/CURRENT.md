# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-01
- **Fix:** KI-042 — two HIGH CVEs in jackson-databind block the image scan
- **Branch:** fix/ki-042-jackson-cves (cut from `main` at `e81c44b`)
- **Step:** BRANCHED
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** NO

## Merge verification before this fix — PASSED WITH TWO NOTED GAPS (tag `ki-039-fixed` on `e81c44b`)
- KI-039: PR #55 merge commit (2 parents). 0 missing commits, 0 diffs, branch alive. On main
  `./mvnw clean verify` 701 tests (524 unit, 177 IT), 0 failed. CI on main run 36815366837: `Build and
  test` and `Dependency scan` pass; **`Image scan (Trivy)` FAILS** on two new HIGH CVEs unrelated to the
  PR (no dependency changed; the same scan passed ten hours earlier), so `publish` was skipped. That
  is KI-042, this fix. The user decided: tag anyway, fix the CVEs next.
- **The cold smoke on main did not run.** My `compose up` collided with the frontend team's stack
  (fixed container names; project `ecomdemo-backend-readonly`), failed, and my script went on to run the
  smoke against THEIR stack for several minutes before I killed it (KI-043). Their services are up and
  healthy (Kafka and catalog-service were restarted by the smoke's failure scenarios); it left test
  data in their databases (at least 4 probe products, and smoke accounts/orders the API does not
  show). main's tree is byte-identical to the PR branch's tip, where the full smoke passed: cold
  468/0/0, kept volumes 470/0/0, kind 424/0/7.

## Checklist (KI-042: scope in its KNOWN_ISSUES detail section)
- [ ] Reproduce first: CI's own scan, locally (pinned Trivy 0.74.0 digest, `scan/<module>:ci` images
      built as CI builds them, `.trivyignore.yaml`) on the unfixed tree: expect 4 HIGH x 8 images
- [ ] Fix: `jackson-bom.version` 3.1.7, `jackson-2-bom.version` 2.21.7 in the parent pom; comment updated
- [ ] The same local scan after the fix: 0 HIGH/CRITICAL in all 8 images
- [ ] `docs/security.md` findings table, `docs/decisions.md`, KI-042 → Fixed
- [ ] Testing protocol steps 1, 3, 4, 7, 8: verify; the app run (compose needs THEIR stack stopped,
      see below; kind does not conflict) + smoke; PR; PR number in the KI status

## Next action
Housekeeping committed. Build the 8 images from this (unfixed) tree and scan them locally with the
pinned Trivy to reproduce the 4 findings per image. Then bump the two properties. Script for the local
scan: `~/.cache/ecomdemo-claude/trivy-local.sh` (not committed; a candidate to commit later).

## ⚠️ Environment notes (this machine) — full list in `docs/process/development-environment.md`
- **BEFORE `docker compose up`: `docker ps`.** The frontend team's clone (~/projects/ecomdemo-backend-readonly)
  uses the SAME container names and ports. If its stack is up, mine fails with a name conflict, and a
  smoke run would hit THEIRS (accounts, products, orders; it stops Kafka and payment-service on purpose).
  Only run `smoke-test.sh` when `up` exited 0 and `docker ps` shows containers labelled
  `com.docker.compose.project=ecomdemo`. Never stop or `down` their project; ask the user. kind does not conflict.
- Verification uses a COLD stack (`down -v`, `up --build --wait`, `.smoke-state` removed).
- kind: `SKIP_BUILD=1 scripts/k8s-up.sh` does NOT restart pods; `kubectl rollout restart` all Deployments.
- Never edit `scripts/smoke-test.sh` while it runs; run a copy. Never print `.env`.
- `pkill -f <pattern>` kills this tool's own shell if the pattern appears in the command line; use PIDs.
- Logs and scratch files go to `~/.cache/ecomdemo-claude/`.
- Repo-local git identity `sujaysp <47919226+sujaysp@users.noreply.github.com>`.
- In this Claude shell `grep` is a broken wrapper function: use `command grep`.
- `~/projects/ecomdemo-web` is the frontend team's workspace: never modified from here.

## ⚠️ Carried, not fixed
Tracked in `docs/KNOWN_ISSUES.md` (open rows: KI-002..011, KI-040, KI-043, and the triage sections).

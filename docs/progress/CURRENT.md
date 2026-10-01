# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-10-01
- **Fix:** KI-042 — two HIGH CVEs in jackson-databind block the image scan
- **Branch:** fix/ki-042-jackson-cves (cut from `main` at `e81c44b`)
- **Step:** PR_OPEN
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #56 https://github.com/mr-sujay-patil/ecomdemo/pull/56
- **Waiting for user:** YES (review of PR #56; and the compose smoke needs the frontend stack down)

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

## Checklist (KI-042)
- [x] Reproduce first: CI's scan run locally (pinned Trivy, scan/<module>:ci images) on the unfixed
      tree: 32 findings = 4 HIGH x 8 images (script: ~/.cache/ecomdemo-claude/trivy-local.sh, not committed)
- [x] Fix: `jackson-bom.version` 3.1.7, `jackson-2-bom.version` 2.21.7; pom comment extended
- [x] The same scan after the fix: 0 findings across all 8 images
- [x] `docs/security.md` (2 rows + a note), `docs/decisions.md` (2), KI-042 Fixed (PR #56), KI-043 and
      KI-044 logged
- [x] verify 701 (524 unit, 177 IT) 0 failed; kind (images rebuilt, 9 Deployments restarted) 424/0/7,
      running pods hold jackson-databind 2.21.7 and 3.1.7
- [ ] Compose cold smoke: NOT run (the frontend team's stack holds the fixed container names and ports)

## Next action
PR #56 is open. STOP: wait for the user's review, and ask the user to get the frontend team's compose
stack stopped before merge verification (it needs a cold compose smoke on main; I must not stop their
stack). After the merge: merge verification (git checks, CI on main incl. `Image scan`, `./mvnw clean
verify`, cold `down -v` smoke), tag `ki-042-fixed`. Then ask what is next: KI-043 (smoke guard), KI-040,
KI-044, or KI-002..011.

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

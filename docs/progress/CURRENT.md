# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 11: Continuous Integration
- **Branch:** feature/phase-11-github-actions
- **Step:** TESTING
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** none yet
- **Waiting for user:** no

## Phase 10 merge verification (passed 2026-09-22)
PR #10 MERGED with a merge commit (35d99a5, 2 parents: d2ab0a2 + 33a5095); branch is an ancestor
of `main`; no commits and no file diffs between branch and `main`; local and remote branches
intact; every "What you'll implement" item present in `main` (`Dockerfile`, `compose.yaml`,
`.dockerignore`, `.env.example`, the Buildpacks comparison in `docs/decisions.md`,
`docs/test-reports/phase-10.md`); `./mvnw clean verify` on `main` -> BUILD SUCCESS, Surefire 190 +
Failsafe 30, 0 failures, 0 skipped; `docker compose up -d --build` on `main` -> both services
healthy, "Successfully validated 6 migrations" / schema v6; `scripts/smoke-test.sh` -> 125 passed,
0 failed, 0 skipped, 0 ERROR in the container log; tag `phase-10-complete` pushed.
(CI-on-main is NOT part of this verification: CI is what this phase adds.)

## Checklist (copied from the phase's "What you'll implement")
- [x] `ci.yml` on every PR: Java 21 with Maven cache, then `./mvnw verify`
- [x] On merge to `main`: build the image and push it to GHCR (commit SHA and `latest`)
- [x] Test reports uploaded as artifacts
- [x] Dependabot for Maven and Actions
- [~] A failing test blocks a PR ✅ (3 runs); a merge publishes an image ⚠️ NOT testable
      before the merge — see docs/test-reports/phase-11.md §5 for the commands to confirm it
- [x] Smoke test addition: the CI workflow for the PR is green
      (optional, in scope: the workflow runs the smoke test against the compose stack)
- [x] Testing protocol run in full + docs/test-reports/phase-11.md
- [x] README section, decisions.md, RECENT.md rotation (Phase 09 archived), tracker -> 🔵
- [ ] PR raised

**User's manual step (after the merge):** add "Require status checks to pass" (the CI job) to the
`main` branch protection in the GitHub UI. From then on, merge only when CI is green.

## Last test run
- 2026-09-22 CI, run 35684121148 (PR opened): Build and test ✅, Publish ⏭ skipped, 73 s.
  Testcontainers started postgres:18-alpine in 1.4 s on the runner; 190 + 30 tests, BUILD SUCCESS.
- 2026-09-22 CI, run 35684245066 (deliberately failing test): Build and test ❌, Publish ⏭
  skipped, test-reports artifact still uploaded (126 KB). PR showed UNSTABLE, not BLOCKED,
  because required_status_checks on main is still null — the user's manual step.
- 2026-09-22 CI, run 35684314280 (the revert): ✅ 57 s, "Cache restored successfully" (73 -> 57 s).
- 2026-09-22 local: `./mvnw clean verify` -> BUILD SUCCESS, 190 + 30, 0 failures, 0 skipped.
- 2026-09-22 local: `scripts/smoke-test.sh` against the compose stack -> 125 passed, 0 failed,
  0 skipped. The script is unchanged this phase.

## Open issues / blockers
- Both opening questions are answered: Testcontainers works on `ubuntu-latest` unchanged, and
  `packages: write` is granted on the publish job alone.
- **⚠️ "a merge publishes an image" is unverified**, and cannot be verified before the merge: the
  publish job is gated on a push to `main`. Confirm it during Phase 11's merge verification —
  `docs/test-reports/phase-11.md` §5 has the exact commands.
- **⚠️ The gate is not enforced yet.** `required_status_checks` on `main` is `null`, so a red
  check shows as UNSTABLE rather than blocking the merge. That is the user's manual step, after
  the merge, in the GitHub UI.

## Decisions this phase (copied to docs/decisions.md ✅ — 11 entries)
- One workflow, two jobs, `publish` with `needs: build` — that is what makes the gate real.
- `contents: read` workflow-wide; `packages: write` on the publish job alone.
- CI runs the identical `./mvnw clean verify`, no CI-only profile or flags.
- `if: always()` on the report upload — otherwise reports appear only when nobody needs them.
- A deliberately failing test was committed and reverted, because an untested claim is not a
  result (testing protocol's honesty rule).
- Two tags: `sha-<short>` immutable for deployments, `latest` mutable for convenience.
- `cancel-in-progress` on PRs but never on main (a cancelled main run means a missing image).
- The optional "smoke test in CI" was NOT built (hard rule 7) and is suggested instead.
- The branch protection change is left to the user, as the phase file assigns it.

## Environment left behind
The compose stack is RUNNING and healthy (`ecomdemo-app`, `ecomdemo-db`), schema v6, data in the
named volume `ecomdemo_postgres-data`. The pre-compose container `ecomdemo-postgres` is stopped,
not deleted. `.env` exists locally with a real JWT_SECRET and is gitignored. No stray Java
processes.

## Next action
Mark PR #11 ready for review (it was opened as a draft so CI would run against a real pull
request), post the Phase Review Report, then STOP for the user's review.

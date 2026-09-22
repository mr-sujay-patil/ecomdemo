# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 11: Continuous Integration
- **Branch:** feature/phase-11-github-actions
- **Step:** BRANCHED
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
- [ ] `ci.yml` on every PR: Java 21 with Maven cache, then `./mvnw verify`
- [ ] On merge to `main`: build the image and push it to GHCR (commit SHA and `latest`)
- [ ] Test reports uploaded as artifacts
- [ ] Dependabot for Maven and Actions
- [ ] A failing test blocks a PR, and a merge publishes an image (the "Done when")
- [ ] Smoke test addition: the CI workflow for the PR is green
      (optional, in scope: the workflow runs the smoke test against the compose stack)
- [ ] Testing protocol run in full + docs/test-reports/phase-11.md
- [ ] README section, decisions.md, RECENT.md rotation (Phase 09 archived), tracker -> 🔵
- [ ] PR raised

**User's manual step (after the merge):** add "Require status checks to pass" (the CI job) to the
`main` branch protection in the GitHub UI. From then on, merge only when CI is green.

## Last test run
- none yet this phase. Baseline inherited from `main`: 190 Surefire + 30 Failsafe, smoke 125.

## Open issues / blockers
- **`./mvnw verify` needs Docker for Testcontainers.** GitHub's `ubuntu-latest` runners have a
  Docker daemon, so this should work unchanged — to be confirmed by a real run, not assumed.
- **GHCR push needs `packages: write`** on `GITHUB_TOKEN`. No secret to create (the built-in
  token is enough), but the permission must be granted per job, not repository-wide.
- **Proving "a failing test blocks a PR"** needs a deliberately failing commit pushed and then
  reverted. Plan: do it on this branch, capture the red run, revert, and record both in the
  report — never leave a broken test behind (hard rule 8).

## Decisions this phase (copied to docs/decisions.md ⬜)
- none yet.

## Environment left behind
The compose stack is RUNNING and healthy (`ecomdemo-app`, `ecomdemo-db`), schema v6, data in the
named volume `ecomdemo_postgres-data`. The pre-compose container `ecomdemo-postgres` is stopped,
not deleted. `.env` exists locally with a real JWT_SECRET and is gitignored. No stray Java
processes.

## Next action
Start step 5 (IMPLEMENTING) of the execution protocol on `feature/phase-11-github-actions`: read
`docs/phases/phase-11-github-actions.md`, then work the checklist above top-down with small
Conventional Commits, beginning with `.github/workflows/ci.yml`.

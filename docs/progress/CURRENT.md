# Current Checkpoint

> The single source of truth for **in-phase** progress. Keep it under ~60 lines. Update and commit it at every step change and before every stop.

- **Updated:** 2026-09-22
- **Phase:** 11: Continuous Integration
- **Branch:** feature/phase-11-github-actions
- **Step:** VERIFYING — **BLOCKED**
  (NOT_STARTED | PREFLIGHT | BRANCHED | PLANNING | IMPLEMENTING | TESTING | PR_OPEN | VERIFYING | WAITING_FOR_USER)
- **PR:** #11 — MERGED (95fd430). Merge verification is INCOMPLETE: see below.
- **Waiting for user:** YES — a GitHub settings change is needed before Phase 11 can be tagged. See "Open issues / blockers".

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
- [x] PR raised (#11) — opened as a draft so CI would run, then marked ready

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

## Open issues / blockers — PHASE 11 IS NOT COMPLETE
1. **🔴 The CI run on `main` for the merge FAILED.** Run 35684930913, one attempt, no re-run.
   `Build and test` ✅; `Publish image to GHCR` ❌ at the "Build and push" step:
   `denied: permission_denied: write_package` pushing `ghcr.io/mr-sujay-patil/ecomdemo:latest`.
   The workflow itself is correct — the job log's "Set up job" shows the token really did get
   `Packages: write`, and `Login Succeeded!`. The denial is GHCR-side.
   **Root cause:** `repos/.../actions/permissions/workflow` -> `default_workflow_permissions:
   "read"`, and the new user-owned package has no Actions write access for this repository.
   **Fix (USER, in GitHub settings — not a code change, and not mine to make):** either
   Settings -> Actions -> General -> Workflow permissions -> "Read and write permissions";
   or the package page -> Package settings -> Manage Actions access -> add `ecomdemo` with the
   **Write** role. Then re-run the failed job (`gh run rerun 35684930913 --failed`).
2. **🟠 A half-published image is in GHCR.** `:latest` EXISTS (a real OCI index, publicly
   pullable) but `:sha-95fd430` does NOT — the push was denied partway through. So `latest`
   currently points at an image no successful run produced, which is a neat illustration of why
   a deployment should never pin `latest`. A successful re-run overwrites it and adds the SHA tag.
3. **⚠️ `required_status_checks` on `main` is still `null`.** A red check shows as UNSTABLE
   rather than blocking the merge — which is exactly what happened here: PR #11 was mergeable
   while this was unenforced. Still the user's manual step.
4. **⚠️ Docker Desktop is not running**, so `./mvnw clean verify` and `scripts/smoke-test.sh`
   on `main` could NOT be run as part of this verification. They must be run before tagging.

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
**Docker Desktop is STOPPED**, so the compose stack is down. Start Docker Desktop, then
`docker compose up -d` to bring `ecomdemo-app` and `ecomdemo-db` back; the named volume
`ecomdemo_postgres-data` still holds the v6 database. The pre-compose container
`ecomdemo-postgres` is stopped, not deleted. `.env` exists locally with a real JWT_SECRET and is
gitignored. No stray Java processes.

## Next action
**STOPPED. Phase 11 is NOT tagged and Phase 12 has NOT been started** — merge verification failed
(blockers 1 and 4 above), and execution-protocol §5 says to stop and report rather than proceed.

When the user has done the GitHub settings change in blocker 1, and Docker Desktop is running:
1. `gh run rerun 35684930913 --failed` and watch it go green.
2. Confirm the image: `docker manifest inspect ghcr.io/mr-sujay-patil/ecomdemo:sha-95fd430`
   and `:latest` — both must exist.
3. `./mvnw clean verify` on `main`, and `scripts/smoke-test.sh` against `docker compose up -d`.
4. Then the git-workflow Verification Checklist (already passed: merge commit with 2 parents,
   ancestor, no diffs, branch intact, artifacts present), tag and push `phase-11-complete`,
   update `docs/test-reports/phase-11.md` §5 with the real result, and start Phase 12
   (`docs/phases/phase-12-sonarqube.md`).

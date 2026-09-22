## Phase 11: Continuous Integration (tag: phase-11-complete, PRs #11 and #13)
**What exists now:** Every PR is built and tested by GitHub Actions before it can be merged, and
every push to `main` publishes an image to GHCR tagged `sha-<short>` and `latest`. Proven, not
assumed: a deliberately failing test was committed, the run went red with the publish job skipped
and the reports still uploaded, and the commit was reverted. Build ~73 s cold, ~57 s with the
Maven cache. No application code changed; 190 + 30 tests and the 125-check smoke test unchanged.
**Key code:** `.github/workflows/ci.yml` — one workflow, two jobs. `build` (checkout@v7,
setup-java@v6 with `cache: maven`, `./mvnw -B clean verify`, upload-artifact@v7 with
`if: always()`, a `$GITHUB_STEP_SUMMARY` table built from the Surefire/Failsafe XML).
`publish` (`needs: build`, `if: push && ref == refs/heads/main`, `packages: write` on that job
alone, login-action@v4 with the built-in `GITHUB_TOKEN`, metadata-action@v6 for the two tags,
build-push-action@v7 with `cache-from/to: type=gha,mode=max`). `.github/dependabot.yml` — weekly
Maven and github-actions, Spring modules grouped.
**Config & infrastructure:** No Maven dependencies, no application changes, schema still V6. The
only new infrastructure is the GHCR package, created by the first publish. `permissions:
contents: read` workflow-wide. `concurrency` cancels superseded PR runs but never `main` runs.
**Tests:** None added or changed — CI runs the existing suite. `scripts/smoke-test.sh` is
untouched this phase. Test report: `docs/test-reports/phase-11.md`.
**Gotchas:** GitHub's ubuntu runners have a Docker daemon, so Testcontainers works with no
`service:` container and no CI-only datasource — the run log shows `postgres:18-alpine` starting
in 1.4 s. `if: always()` on the upload step is what makes reports available from a RED build
(126 KB artifact captured from the failing run). A red check shows the PR as `UNSTABLE`, NOT
`BLOCKED`, until `required_status_checks` is added to the branch protection — it was `null` at the
time of writing, which is the user's manual step. `needs: build` is the only thing stopping a red
commit from publishing an image.
**Follow-ups (not done, out of scope):** running the smoke test against the compose stack in CI —
optional in the phase file, deliberately skipped, and the most obviously worthwhile next addition.
Image vulnerability scanning — Phase 31. Actual deployment — Phases 25-26. Pinning actions by
commit SHA rather than major version — not planned.

# Phase 11 Test Report: Continuous Integration

- **Date:** 2026-09-22
- **Branch:** `feature/phase-11-github-actions`
- **Toolchain:** GitHub Actions on `ubuntu-latest`, `actions/checkout@v7`,
  `actions/setup-java@v6` (Temurin 21, `cache: maven`), `actions/upload-artifact@v7`,
  `docker/login-action@v4`, `docker/metadata-action@v6`, `docker/build-push-action@v7`;
  locally JDK 21, Maven Wrapper 3.9.16, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ everything testable before the merge passed. **One item is ⚠️ and cannot be
  verified until the merge happens** — see §5.

## 1. Full regression — `./mvnw clean verify`

Locally:

```
Tests run: 190, Failures: 0, Errors: 0, Skipped: 0     (surefire)
Tests run: 30,  Failures: 0, Errors: 0, Skipped: 0     (failsafe)
BUILD SUCCESS
```

And in CI, which is the point of the phase — the identical command, on a clean runner:

```
[INFO] Tests run: 190, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  59.120 s
```

No Java test was added or changed this phase. `scripts/smoke-test.sh` is untouched.

## 2. Testcontainers works on the runner

This was the open question at the start of the phase, recorded as a blocker rather than assumed.
It does, with no `service:` container and no CI-only datasource:

```
tc.testcontainers/ryuk:0.14.0 : Creating container for image: testcontainers/ryuk:0.14.0
tc.postgres:18-alpine         : Creating container for image: postgres:18-alpine
tc.postgres:18-alpine         : Container postgres:18-alpine started in PT1.42797326S
```

That is what allows CI to run the *identical* `./mvnw clean verify` a developer runs, rather than
a CI-only variant — the difference between a failure being reproducible locally and being a
half-day of guessing.

## 3. "Done when", part one: a failing test blocks a PR

✅ Verified by doing it, not by reading the configuration.

A deliberately failing test was committed (`31a596e`), and reverted in the very next commit
(`7f727ff`); both remain in the branch history as the evidence.

| Run | Trigger | Build and test | Publish image | Elapsed |
|---|---|---|---|---|
| [35684121148](https://github.com/mr-sujay-patil/ecomdemo/actions/runs/35684121148) | PR opened | ✅ success | ⏭ skipped | 73 s |
| [35684245066](https://github.com/mr-sujay-patil/ecomdemo/actions/runs/35684245066) | the failing test | ❌ **failure** | ⏭ skipped | — |
| [35684314280](https://github.com/mr-sujay-patil/ecomdemo/actions/runs/35684314280) | the revert | ✅ success | ⏭ skipped | 57 s |

Three things worth drawing out of the red run:

- **The publish job was skipped.** `needs: build` is the only thing standing between a broken
  commit and a published image, and it held.
- **The test reports were still uploaded** — a 126 KB `test-reports` artifact from the failing
  run. `if: always()` is what makes that true; the default would have skipped the upload in
  exactly the case where the reports matter.
- **The PR showed `mergeStateStatus: UNSTABLE`, not `BLOCKED`.** That is honest and important:
  the check failed and is visible on the PR, but the merge is not yet *prevented*, because
  `required_status_checks` on the `main` branch protection is currently `null`:

  ```json
  { "enforce_admins": true, "required_pull_request_reviews": true, "required_status_checks": null }
  ```

  Turning a red check into a blocked merge is the user's manual step for this phase. Until it is
  done, CI reports but does not enforce.

## 4. Caching

| Run | Cache | Elapsed |
|---|---|---|
| First (cold) | `maven cache is not found` → saved at the end | 73 s |
| Third (warm) | `Cache restored successfully` | **57 s** |

~20% off, and ~200 MB not re-downloaded from Maven Central per run. The key is derived from the
pom files, so it survives code changes and rebuilds when a dependency moves — the same idea as the
Dockerfile's layer ordering, applied to `~/.m2`.

## 5. ⚠️ "Done when", part two: a merge publishes an image

**Not verifiable before the merge.** The publish job is deliberately gated on
`github.event_name == 'push' && github.ref == 'refs/heads/main'`, and that event does not exist
until the PR is merged. Making it testable earlier would mean relaxing exactly the condition that
stops an unreviewed pull request from publishing.

What *has* been verified: the job exists, is correctly skipped on every pull-request run (three
times, above), is gated on `needs: build`, and holds `packages: write`. The image itself builds
from this same `Dockerfile` locally in 34 s (Phase 10's report).

**Manual step to confirm it after merging** — this is also part of Phase 11's own merge
verification:

```bash
gh run list --branch main --limit 1          # the run triggered by the merge
gh run view <id> --json jobs --jq '.jobs[]|{name,conclusion}'
#   expect: "Build and test" success, "Publish image to GHCR" success

gh api users/mr-sujay-patil/packages/container/ecomdemo/versions --jq '.[0].metadata.container.tags'
#   expect: ["latest", "sha-<short>"]

docker pull ghcr.io/mr-sujay-patil/ecomdemo:latest
```

## 6. The run summary

Each run writes a table to `$GITHUB_STEP_SUMMARY`, so the counts are on the run's page without
opening a log or downloading an artifact. The script was tested locally against real reports
before being committed:

```
## Test results

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| surefire | 190 | 0 | 0 | 0 |
| failsafe | 30 | 0 | 0 | 0 |
```

## 7. Smoke test

`scripts/smoke-test.sh` is **unchanged this phase**. The phase file's smoke-test addition is "the
CI workflow for the PR is green", which is a property of the run rather than of the script — and
it is green (§3).

The optional addition, running the smoke test against the compose stack inside CI, was
**deliberately not built**: the phase file marks it optional and the project's rule is to
implement only the phase's scope and suggest the rest. It is the most obviously worthwhile next
thing to add, and is suggested in the PR.

## 8. Clean-up

- The deliberately failing test is gone; `git status` is clean and the tree is exactly as it was
  found. `src/test/java/com/ecomdemo/CiGateTest.java` does not exist.
- The compose stack is left running and healthy at schema v6, as Phase 10 left it.
- The PR was opened as a **draft** so that CI would run against a real pull request while the
  phase was still being built, and marked ready for review once it was.
- No stray Java processes on the host.

## 9. What is deferred, and why

| Item | Status |
|---|---|
| A failing test blocks a PR | ✅ verified, three runs |
| A merge publishes an image | ⚠️ **cannot be tested before the merge** — §5 has the commands |
| "Require status checks to pass" on `main` | ⚠️ the user's manual step, in the GitHub UI |
| Smoke test in CI | not built — optional in the phase file, suggested in the PR |

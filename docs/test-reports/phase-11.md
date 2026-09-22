# Phase 11 Test Report: Continuous Integration

- **Date:** 2026-09-22
- **Branch:** `feature/phase-11-github-actions`
- **Toolchain:** GitHub Actions on `ubuntu-latest`, `actions/checkout@v7`,
  `actions/setup-java@v6` (Temurin 21, `cache: maven`), `actions/upload-artifact@v7`,
  `docker/login-action@v4`, `docker/metadata-action@v6`, `docker/build-push-action@v7`;
  locally JDK 21, Maven Wrapper 3.9.16, Docker 29.7.2, Compose v5.4.0
- **Result:** ✅ everything passed. §5 was ⚠️ when this report was first written and is now
  **resolved** — it took three failed runs and a deleted package to get there, and that story is
  the most useful thing in this report.

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

## 5. "Done when", part two: a merge publishes an image — ✅, eventually

This section was written as ⚠️ before the merge, because the publish job is gated on
`github.event_name == 'push' && github.ref == 'refs/heads/main'` and that event does not exist
until the PR is merged. Making it testable earlier would have meant relaxing the exact condition
that stops an unreviewed pull request from publishing.

It then failed three times, and the diagnosis was wrong twice. The sequence is worth recording in
full, because "the config looks right" was true throughout.

| Attempt | Trigger | Build and test | Publish |
|---|---|---|---|
| Run 35684930913 | merge of PR #11 (`95fd430`) | ✅ | ❌ `denied: permission_denied: write_package` |
| Run 35687555954 | merge of PR #12 (`5748b56`) | ✅ | ❌ same error |
| Run 35687555954, re-run | after setting the repo token default to `write` | ✅ | ❌ **same error** |
| Run 35687555954, re-run | after **deleting** the GHCR package | ✅ | ✅ **success** |

**What the log said every time**, including the very first failure:

```
GITHUB_TOKEN Permissions
  Contents: read
  Packages: write          <-- the token DID have it, from the start
Login Succeeded!
#26 ERROR: failed to push ghcr.io/mr-sujay-patil/ecomdemo:latest:
          denied: permission_denied: write_package
```

**The wrong diagnosis.** The repository's `default_workflow_permissions` was `read`, so that
looked like the cause. Setting it to `write` changed nothing — and in hindsight the log already
said so: the workflow's own `permissions:` block was elevating the job to `Packages: write`
regardless of the repository default. The repository setting was never the binding constraint.

**The actual cause.** The first failed push was *partial*: it created the package
`ghcr.io/mr-sujay-patil/ecomdemo` and wrote a `latest` tag, then was denied. GHCR authorises a
push against the package's own **Actions access** list, not only against the token's scope — and
a package created in that half-finished state had no write access for the repository. Every
later push was then denied by the package that the first failure had left behind.

**The fix**: delete the package and let a clean run create it. A push that creates a package from
a workflow links it to the source repository automatically; the surgical alternative (package
settings -> Manage Actions access -> add the repository with the Write role) would also have
worked.

**Verified afterwards:**

```
latest           EXISTS
sha-5748b56      EXISTS
latest=8fff98009bb1  sha-5748b56=8fff98009bb1
same manifest ✓
```

Both tags resolve to the same manifest, which is what `docker/metadata-action` is supposed to
produce: one image, two names.

**Two things this taught that no amount of configuration review would have.**

1. *The half-published `latest` was real.* Between the first failure and the fix, `latest`
   existed in GHCR and was publicly pullable, pointing at an image no successful run had
   produced. The README argues that a deployment must never pin `latest`; this is that argument
   as an incident rather than as a paragraph.
2. *`needs: build` held throughout.* Every one of these failures was in `publish`, with
   `Build and test` green — so the gate never had to stop anything. But the converse also shows
   up here: a red `publish` job does not stop a merge either, and PR #12 was merged while the
   publish job for #11 was still failing.

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
| A merge publishes an image | ✅ verified after the fix in §5 — both tags, same manifest |
| `./mvnw clean verify` on `main` | ✅ 190 + 30, 0 failures, 0 skipped, 23.6 s |
| `scripts/smoke-test.sh` on `main` | ✅ 125 passed, 0 failed, 0 skipped, 0 ERROR in the container log |
| "Require status checks to pass" on `main` | ⚠️ **still outstanding** — the user's manual step. `required_status_checks` is `null`, so CI reports but does not block; both PR #11 and #12 were merged with a red run |
| Smoke test in CI | not built — optional in the phase file, suggested in the PR |

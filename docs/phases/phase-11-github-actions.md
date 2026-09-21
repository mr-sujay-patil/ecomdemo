# Phase 11: Continuous Integration

| | |
|---|---|
| **Stage** | Stage 4: Quality & Delivery |
| **Technology** | GitHub Actions |
| **Branch** | `feature/phase-11-github-actions` |
| **PR title** | `Phase 11: Continuous Integration` |
| **Requires** | `phase-10-complete` tag exists on `main` |
| **Completion tag** | `phase-11-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** GitHub Actions

**Goal:** Automatically build and test every change.

**What you'll implement**
- `ci.yml` on every PR: Java 21 with Maven cache, then `./mvnw verify`.
- On merge to `main`: build the image and push it to GHCR (tagged with the commit SHA and `latest`).
- Add "require status checks to pass" to the `main` branch protection (done by you in the UI).
- Test reports uploaded as artifacts.
- Dependabot for Maven and Actions.
- **From this phase on, a PR may be merged only when CI is green.**

**Concepts to understand**
- CI vs CD
- Workflows, jobs, steps, and runners
- Secrets and `GITHUB_TOKEN` permissions
- Dependency caching
- Image tagging

**Done when**
- A failing test blocks a PR, and a merge publishes an image.

## Smoke test additions (`scripts/smoke-test.sh`)

The CI workflow for the PR is green. Optional: the workflow runs the smoke test against the Compose stack.

## Your manual steps (user)

After the PR is merged, add "Require status checks to pass" (the CI job) to the `main` branch protection. From now on, merge only when CI is green.

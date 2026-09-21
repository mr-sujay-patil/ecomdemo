# Phase 12: Code Quality

| | |
|---|---|
| **Stage** | Stage 4: Quality & Delivery |
| **Technology** | SonarQube + JaCoCo |
| **Branch** | `feature/phase-12-sonarqube` |
| **PR title** | `Phase 12: Code Quality` |
| **Requires** | `phase-11-complete` tag exists on `main` |
| **Completion tag** | `phase-12-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** SonarQube (+ JaCoCo)

**Goal:** Measure and enforce code quality.

**What you'll implement**
- SonarQube Community Edition in `compose.sonar.yaml`.
- The JaCoCo plugin, with analysis through `./mvnw sonar:sonar`.
- Fix the reported bugs, smells, and hotspots.
- A quality gate (for example, at least 70% coverage on new code and no new critical issues).
- Optional: SonarQube Cloud in GitHub Actions with PR decoration.

**Concepts to understand**
- Bugs vs vulnerabilities vs smells vs hotspots
- Complexity metrics
- Technical debt
- Quality gates on new code
- The limits of coverage

**Done when**
- The project passes the quality gate, and the results are documented.

## Smoke test additions (`scripts/smoke-test.sh`)

No new checks. The existing script must still pass, and the quality gate result is added to the test report.

## Your manual steps (user)

If you use SonarQube Cloud: create the account and project, and set `SONAR_TOKEN` as an environment variable or GitHub secret yourself.

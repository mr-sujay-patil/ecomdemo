# Phase 31: Security Scanning

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | OWASP Dependency-Check + Trivy |
| **Branch** | `feature/phase-31-security-scanning` |
| **PR title** | `Phase 31: Security Scanning` |
| **Requires** | `phase-30-complete` tag exists on `main` |
| **Completion tag** | `phase-31-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** OWASP Dependency-Check + Trivy

**Goal:** Block vulnerable dependencies and images.

**What you'll implement**
- Dependency-Check in CI (fail on high severity).
- Trivy image scans in CI.
- Every finding fixed or suppressed with a justification.
- An OWASP API Top 10 review in `docs/security.md`.

**Concepts to understand**
- CVEs and CVSS
- Supply-chain security and SBOMs
- Shift-left security
- The OWASP API Top 10

**Done when**
- CI blocks vulnerable builds, and the security document is complete.

## Smoke test additions (`scripts/smoke-test.sh`)

No new checks. The scan results are added to the test report.

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`.

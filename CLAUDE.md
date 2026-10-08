# CLAUDE.md: EcomDemo

A learning project: an e-commerce app evolving from a simple Spring Boot monolith into a production-grade distributed system, **one technology per phase**. The user is learning; explain the "why" behind decisions.

**Stack:** Java 21 · latest stable Spring Boot 4.x · Maven Wrapper · Git + GitHub (`gh`)

## 🔴 Hard rules (never break; if a rule can't be followed, STOP and ask)

1. Every unit of work gets its own branch cut from the latest `main`, and all its changes are made only there (related fixes may share one branch and PR, but only when the user asks: workflow rule 11 below):
   a phase → `feature/phase-XX-<slug>`; a defect from `docs/KNOWN_ISSUES.md` → `fix/ki-XXX-<slug>`;
   a process or docs change outside both → `chore/<slug>` (only when the user asks for it).
2. When the phase or fix is complete: push and raise a PR to `main`, then **STOP** for the user's review.
3. **Never merge** unless the user says `approved, merge it`. Only merge commits (`gh pr merge --merge`), never squash or rebase.
4. Start the next phase or fix only after the user says to continue **and** merge verification proves every change of the previous one is in `main`. One open PR at a time; the exceptions the user has set up (a PR blocked by a red CI it did not cause, and chores the user asks for) are in `docs/process/git-workflow.md`.
5. **Never delete any branch** (local or remote). Never use `--delete-branch`.
6. **Never commit to `main`** (the only exception is the Phase 0 bootstrap commit). Never force-push, never rewrite history.
7. Implement **only** the current phase's or fix's scope. Suggest extras; don't build them. A defect found along the way goes into `docs/KNOWN_ISSUES.md`; it isn't fixed in passing.
8. Never disable or delete tests to get green. Never report a check as passed without running it.
9. Never put secrets in code, commits, PRs, or chat. The user provides them as environment variables.

## 🔁 Session start (always)

Follow the resume sequence in `docs/process/execution-protocol.md` (section 1):
Git state → `docs/progress/CURRENT.md` → `docs/progress/RECENT.md` → **only** the current `docs/phases/phase-XX-*.md`
(or, for a fix, only its row and detail section in `docs/KNOWN_ISSUES.md`).
After auto-compaction, or whenever unsure of the state, rerun this sequence before acting.

## 📂 Where things are

| Need | File |
|---|---|
| Lifecycle, user commands, stop points | `docs/process/execution-protocol.md` |
| Git rules, commands, verification checklist | `docs/process/git-workflow.md` |
| Testing before a PR and after a merge | `docs/process/testing-protocol.md` |
| What to load, checkpoint and summary rules | `docs/process/context-management.md` |
| Machine setup, WSL2, and which machine ran a result | `docs/process/development-environment.md` |
| Current phase scope | `docs/phases/phase-XX-*.md` (one file) |
| Known defects, gaps, and a fix's scope | `docs/KNOWN_ISSUES.md` |
| In-phase (or in-fix) checkpoint | `docs/progress/CURRENT.md` |
| Last two phases' summaries | `docs/progress/RECENT.md` |
| Long-lived decisions | `docs/decisions.md` (`grep`, don't load in full) |
| Tracker and overview | `docs/ROADMAP.md` (edit the tracker row; don't read in full) |

## 💾 Checkpointing

Update and commit `docs/progress/CURRENT.md` at every step change, after each checklist item, and **before every stop**. Its "Next action" must let a fresh session continue with zero conversation history.

## 🧹 Context hygiene

Don't read other phase files or archives unless needed. Don't paste full logs or files into the conversation; use `grep`, `tail`, `git diff --stat`, and line ranges.

## ✍️ Conventions

- Base package `com.ecomdemo`, package-by-feature, Controller → Service → Repository, DTOs as records
- Constructor injection only, `BigDecimal` for money, no Lombok
- Conventional Commits, and stable GA dependencies compatible with the Spring Boot version
- Build: `./mvnw clean verify` · Smoke test: `scripts/smoke-test.sh`

## Workflow rules to reduce cycle time

### Local builds and tests
1. While iterating, build and test only the affected module: ./mvnw -pl <module> -am verify. Run the full ./mvnw -B verify once, just before opening the PR.
2. To reproduce a CI failure, run the exact command from .github/workflows/ci.yml so there is no translation between local and CI.
3. If a change touches the API used by performance-tests, also run ./mvnw -B -f performance-tests/pom.xml test-compile before opening the PR.
4. Never use Thread.sleep in tests. Use Awaitility polling.

### Kubernetes smoke test
5. Run it only if the change touches deployment manifests, configuration, startup, or communication between services. Otherwise skip it and state why in the PR description.
6. When debugging a smoke-test failure, rebuild and roll out only the affected service and re-run only the failing check. Run the full smoke test once at the end.
7. Rebuild and kind-load images only for services whose code changed.

### Long-running commands and CI
8. Run any command expected to take more than a few minutes (smoke test, full verify, waiting on CI) in the background, then check its status. Do not block on it in the foreground or let it hit a tool timeout.
9. Before asking me to approve a PR, confirm its CI run is green. If it is red, fix it first.
10. After a merge, do not wait for the main-branch run unless the next task depends on the published image.

### PRs
11. Group related fixes into a single PR where reasonable, instead of one PR per fix.

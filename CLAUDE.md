# CLAUDE.md: EcomDemo

A learning project: an e-commerce app evolving from a simple Spring Boot monolith into a production-grade distributed system, **one technology per phase**. The user is learning; explain the "why" behind decisions.

**Stack:** Java 21 · latest stable Spring Boot 4.x · Maven Wrapper · Git + GitHub (`gh`)

## 🔴 Hard rules (never break; if a rule can't be followed, STOP and ask)

1. Every phase: cut `feature/phase-XX-<slug>` from the latest `main`, and make all changes only there.
2. When the phase is complete: push and raise a PR to `main`, then **STOP** for the user's review.
3. **Never merge** unless the user says `approved, merge it`. Only merge commits (`gh pr merge --merge`), never squash or rebase.
4. Start the next phase only after the user says to continue **and** merge verification proves every change of the phase is in `main`.
5. **Never delete any branch** (local or remote). Never use `--delete-branch`.
6. **Never commit to `main`** (the only exception is the Phase 0 bootstrap commit). Never force-push, never rewrite history.
7. Implement **only** the current phase's scope. Suggest extras; don't build them.
8. Never disable or delete tests to get green. Never report a check as passed without running it.
9. Never put secrets in code, commits, PRs, or chat. The user provides them as environment variables.

## 🔁 Session start (always)

Follow the resume sequence in `docs/process/execution-protocol.md` (section 1):
Git state → `docs/progress/CURRENT.md` → `docs/progress/RECENT.md` → **only** the current `docs/phases/phase-XX-*.md`.
After auto-compaction, or whenever unsure of the state, rerun this sequence before acting.

## 📂 Where things are

| Need | File |
|---|---|
| Lifecycle, user commands, stop points | `docs/process/execution-protocol.md` |
| Git rules, commands, verification checklist | `docs/process/git-workflow.md` |
| Testing before a PR and after a merge | `docs/process/testing-protocol.md` |
| What to load, checkpoint and summary rules | `docs/process/context-management.md` |
| Current phase scope | `docs/phases/phase-XX-*.md` (one file) |
| In-phase checkpoint | `docs/progress/CURRENT.md` |
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

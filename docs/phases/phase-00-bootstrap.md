# Phase 0: Repository Bootstrap

| | |
|---|---|
| **Stage** | Stage 0: Repository Bootstrap |
| **Technology** | Git + GitHub + GitHub CLI |
| **Branch** | `main` (bootstrap commit only) |
| **PR title** | `Phase 00: Repository Bootstrap` |
| **Requires** | None (first phase) |
| **Completion tag** | `phase-00-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for every Git action.

**Technology:** Git + GitHub + GitHub CLI (`gh`)

**Goal:** Create the repository and the rules every later phase depends on. This is the **only** phase that commits directly to `main`, because `main` must exist before a branch can be cut from it.

**Starting point:** the folder already contains this documentation package (`CLAUDE.md` and `docs/`). Do not rewrite these files; commit them as they are.

**What you'll implement**
- `git init -b main` in the project folder.
- Add the remaining bootstrap files:
  - `README.md`: project purpose, a link to `docs/ROADMAP.md`, and a "Current status" line
  - `.gitignore`: Maven, IntelliJ, VS Code, OS files, `.env`, `target/`
  - `.github/pull_request_template.md`: Phase, What changed, How it was tested (link to the test report), Concepts learned, and a checklist (tests green, smoke test green, docs updated, no secrets)
  - `scripts/`: an empty folder with a `.gitkeep` file
- One initial commit on `main`: `chore: bootstrap repository and roadmap`.
- `gh repo create ecomdemo --source . --push` (ask the user for public or private before running).
- **STOP** and ask the user to apply the GitHub settings (see "Your manual steps").
- After the user replies `done`, verify the settings through the API:
  - `gh api repos/{owner}/{repo}` must show `delete_branch_on_merge: false`, `allow_squash_merge: false`, `allow_rebase_merge: false`, and `allow_merge_commit: true`.
  - `gh api repos/{owner}/{repo}/branches/main/protection` must show protection enabled.
- Tag `phase-00-complete`, push the tag, and stop with a short report.
- Keep `docs/progress/CURRENT.md` updated locally during this phase. It is included in the bootstrap commit. Marking Phase 0 as ✅ in the tracker happens as the first commit on Phase 1's branch, like every other phase.

**Concepts to understand**
- Branches, merges, and merge commits vs squash vs rebase merges
- Pull Requests and the code review flow
- Branch protection rules
- Conventional Commits
- Annotated tags

**Done when**
- The repository exists on GitHub with all bootstrap files on `main`.
- A direct push to `main` is rejected.
- Automatic branch deletion is off, and only merge commits are allowed (verified through `gh api`).
- Tag `phase-00-complete` is on GitHub.

## Smoke test additions (`scripts/smoke-test.sh`)

Not applicable; the smoke test is created in Phase 1.

## Your manual steps (user)

Run `gh auth login` before kickoff. When Claude Code stops, apply the GitHub settings in the UI: branch protection on `main` (require PR, block force push, block deletion), disable "Automatically delete head branches", allow merge commits only. Then reply `done`.
